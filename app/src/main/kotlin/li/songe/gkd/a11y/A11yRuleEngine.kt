package li.songe.gkd.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.META
import li.songe.gkd.bypass.BypassActionBudget
import li.songe.gkd.bypass.BypassAdContextLevel
import li.songe.gkd.bypass.BypassAdContextTracker
import li.songe.gkd.bypass.BypassOutcome
import li.songe.gkd.bypass.BypassOutcomeVerifier
import li.songe.gkd.bypass.BypassPerfTrace
import li.songe.gkd.bypass.BypassDiagnostics
import li.songe.gkd.bypass.BypassDetectionSessions
import li.songe.gkd.bypass.BypassRejectReason
import li.songe.gkd.bypass.BypassExitCandidateType
import li.songe.gkd.bypass.BypassExitClassifier
import li.songe.gkd.bypass.BypassRulePolicyResolver
import li.songe.gkd.bypass.BypassRuleTrust
import li.songe.gkd.bypass.BypassStrategyGate
import li.songe.gkd.bypass.BypassTeachRules
import li.songe.gkd.bypass.FailureReason
import li.songe.gkd.data.ActionPerformer
import li.songe.gkd.data.ActionResult
import li.songe.gkd.data.AppRule
import li.songe.gkd.data.GkdAction
import li.songe.gkd.data.ResolvedRule
import li.songe.gkd.data.RpcError
import li.songe.gkd.data.RuleStatus
import li.songe.gkd.isActivityVisible
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.EventService
import li.songe.gkd.service.topAppIdFlow
import li.songe.gkd.shizuku.casted
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.shizuku.uiAutomationFlow
import li.songe.gkd.store.actualBlockA11yAppList
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.AutomatorModeOption
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.runMainPost
import li.songe.gkd.util.showActionToast
import li.songe.gkd.util.systemUiAppId
import li.songe.selector.MatchOption
import li.songe.selector.Selector
import java.util.concurrent.Executors
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume


private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val queryDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val actionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

private val latestServiceMode = atomic(0)
private val latestServiceTime = atomic(0L)

class A11yRuleEngine(val service: A11yCommonImpl) {
    private val a11yContext = A11yContext(this)
    private val effective get() = latestServiceMode.value == service.mode.value
    private val hasOthersService = when (service.mode) {
        AutomatorModeOption.A11yMode -> uiAutomationFlow.value != null
        AutomatorModeOption.AutomationMode -> A11yService.instance != null
    }

    fun onAppChanged() {
        BypassActionBudget.clear()
    }

    fun onA11yConnected() {
        val serviceTime = System.currentTimeMillis()
        latestServiceMode.value = service.mode.value
        latestServiceTime.value = serviceTime
        if (storeFlow.value.enableBlockA11yAppList && !actualBlockA11yAppList.contains(topAppIdFlow.value)) {
            startQueryJob(byForced = true)
        }
        runMainPost(1000L) {// 共存 1000ms, 等待另一个服务稳定
            if (latestServiceTime.value == serviceTime) {
                when (service.mode) {
                    AutomatorModeOption.A11yMode -> uiAutomationFlow.value?.shutdown(true)
                    AutomatorModeOption.AutomationMode -> A11yService.instance?.shutdown(true)
                }
            }
        }
    }

    fun onScreenForcedActive() {
        // 关闭屏幕 -> Activity::onStop -> 点亮屏幕 -> Activity::onStart -> Activity::onResume
        val a = topActivityFlow.value
        synchronized(topActivityFlow) {
            updateTopActivity(
                a.appId,
                a.activityId,
                scene = ActivityScene.ScreenOn
            )
        }
        startQueryJob()
    }

    val safeActiveWindow: AccessibilityNodeInfo?
        get() = try {
            // 某些应用耗时 554ms
            // java.lang.SecurityException: Call from user 0 as user -2 without permission INTERACT_ACROSS_USERS or INTERACT_ACROSS_USERS_FULL not allowed.
            service.windowNodeInfo?.setGeneratedTime()
        } catch (_: Throwable) {
            null
        }.apply {
            a11yContext.rootCache.value = this
        }

    val safeActiveWindowAppId: String?
        get() = safeActiveWindow?.packageName?.toString()

    private val scope get() = service.scope

    @Volatile
    private var latestStateEvent: A11yEvent? = null
    private var lastContentEventTime = 0L
    private var lastEventTime = 0L
    private val eventDeque = ArrayDeque<A11yEvent>()
    fun onA11yEvent(event: AccessibilityEvent?) {
        BypassPerfTrace.appSwitch()
        if (!effective) return
        if (!event.isUseful()) return
        // 拒绝副屏无障碍事件
        if (AndroidTarget.TIRAMISU && event.displayId != Display.DEFAULT_DISPLAY) return
        onA11yFeatEvent(event)
        if (event.eventType == CONTENT_CHANGED) {
            if (!isInteractive) return // 屏幕关闭后仍然有无障碍事件 type:2048, time:8094, app:com.miui.aod, cls:android.widget.TextView
            if (event.packageName == systemUiAppId && event.packageName != topActivityFlow.value.appId) return
        }
        // 过滤部分输入法事件
        if (event.packageName == imeAppId && topActivityFlow.value.appId != imeAppId) {
            if (event.recordCount == 0 && event.action == 0 && !event.isFullScreen) return
        }
        // 直接丢弃自身事件，自行更新 topActivity
        if ((event.eventType == CONTENT_CHANGED || !isActivityVisible) && event.packageName == META.appId) return

        val a11yEvent = event.toA11yEvent() ?: return
        if (a11yEvent.type == CONTENT_CHANGED) {
            // 防止 content 类型事件过快
            if (a11yEvent.time - lastContentEventTime < 100 && a11yEvent.time - appChangeTime > 5000 && a11yEvent.time - lastTriggerTime > 3000) {
                return
            }
            lastContentEventTime = a11yEvent.time
        }
        EventService.logEvent(event)
        if (META.debuggable) {
            Log.d(
                "onNewA11yEvent",
                "type:${event.eventType}, time:${event.eventTime - lastEventTime}, app:${event.packageName}, cls:${event.className}"
            )
        }
        if (event.eventTime < lastEventTime) {
            // 某些应用会发送负时间事件, 直接丢弃
            // type:32, time:-104, app:com.miui.home, cls:com.miui.home.launcher.Launcher
            return
        }
        lastEventTime = event.eventTime
        if (event.eventType == STATE_CHANGED) {
            latestStateEvent = a11yEvent
        }
        synchronized(eventDeque) { eventDeque.addLast(a11yEvent) }
        scope.launch(eventDispatcher) { consumeEvent(a11yEvent) }
    }

    private val queryEvents = mutableListOf<A11yEvent>()
    private suspend fun consumeEvent(headEvent: A11yEvent) {
        val consumedEvents = synchronized(eventDeque) {
            if (eventDeque.firstOrNull() !== headEvent) return
            eventDeque.filter { it.sameAs(headEvent) }.apply {
                repeat(size) { eventDeque.removeFirst() }
            }
        }
        val latestEvent = consumedEvents.last()
        val evAppId = latestEvent.appId
        val evActivityId = latestEvent.name
        val oldAppId = topActivityFlow.value.appId
        val rightAppId = if (oldAppId == evAppId) {
            evAppId
        } else {
            getTimeoutAppId() ?: return
        }
        if (rightAppId == evAppId) {
            if (latestEvent.type == STATE_CHANGED) {
                synchronized(topActivityFlow) {
                    // tv.danmaku.bili, com.miui.home, com.miui.home.launcher.Launcher
                    if (isActivity(evAppId, evActivityId)) {
                        updateTopActivity(evAppId, evActivityId)
                    }
                }
            }
        }
        if (rightAppId != topActivityFlow.value.appId) {
            synchronized(topActivityFlow) {
                // 从 锁屏，下拉通知栏 返回等情况, 应用不会发送事件, 但是系统组件会发送事件
                val topCpn = shizukuContextFlow.value.topCpn()
                if (topCpn?.packageName == rightAppId) {
                    updateTopActivity(topCpn.packageName, topCpn.className)
                } else {
                    updateTopActivity(rightAppId, null)
                }
            }
        }
        val activityRule = activityRuleFlow.value
        if (!storeFlow.value.enableMatch) {
            BypassDiagnostics.record(FailureReason.MASTER_DISABLED, evAppId, evActivityId, "matching_disabled")
            return
        }
        if (evAppId != rightAppId || activityRule.skipConsumeEvent) {
            return
        }
        synchronized(queryEvents) { queryEvents.addAll(consumedEvents) }
        a11yContext.interruptKey++
        startQueryJob(byEvent = latestEvent)
    }

    private var lastGetAppIdTime = 0L
    private var lastAppId: String? = null
    private suspend fun getTimeoutAppId(): String? {
        if (lastAppId != null && System.currentTimeMillis() - lastGetAppIdTime <= 100) return lastAppId
        // 某些应用通过无障碍获取 safeActiveWindow 耗时长，导致多个事件连续堆积堵塞，无法检测到 appId 切换导致状态异常
        // https://github.com/gkd-kit/gkd/issues/622
        lastAppId = withTimeoutOrNull(100) {
            runInterruptible(Dispatchers.IO) { safeActiveWindowAppId }
        } ?: shizukuContextFlow.value.topCpn()?.packageName
        lastGetAppIdTime = System.currentTimeMillis()
        return lastAppId
    }

    // 某些场景耗时 5000 ms
    private suspend fun getTimeoutActiveWindow(): AccessibilityNodeInfo? {
        return suspendCancellableCoroutine { s ->
            val temp = atomic<Continuation<AccessibilityNodeInfo?>?>(s)
            scope.launch(Dispatchers.IO) {
                delay(500L)
                if (s.isActive) {
                    temp.getAndUpdate { null }?.resume(null)
                }
            }
            scope.launch(Dispatchers.IO) {
                val a = safeActiveWindow
                if (s.isActive) {
                    temp.getAndUpdate { null }?.resume(a)
                }
            }
        }
    }

    @Volatile
    private var querying = false

    @Synchronized
    private fun startQueryJob(
        byEvent: A11yEvent? = null,
        byForced: Boolean = false,
        byDelayRule: ResolvedRule? = null,
    ) {
        if (!effective) return
        // Expire armed teach verifications whose window ended without success.
        BypassTeachRules.checkVerificationWindows()
        if (!storeFlow.value.enableMatch) {
            BypassDiagnostics.record(FailureReason.MASTER_DISABLED, detail = "matching_disabled")
            return
        }
        if (activityRuleFlow.value.currentRules.isEmpty()) {
            BypassDiagnostics.record(FailureReason.NO_RULE_FOR_APP, detail = "no_resolved_rules")
            return
        }
        if (querying) return
        // 无障碍从零启动时获取 safeActiveWindow 非常耗时
        if (byEvent == null && service.justStarted && !hasOthersService) return checkFutureStartJob()
        scope.launchTry(queryDispatcher) {
            querying = true
            val st = if (META.debuggable) System.currentTimeMillis() else 0L
            try {
                if (META.debuggable) {
                    Log.d(
                        "A11yRuleEngine",
                        "startQueryJob start byEvent=${byEvent != null}, byForced=$byForced, byDelayRule=${byDelayRule != null}"
                    )
                }
                queryAction(byEvent, byForced, byDelayRule)
            } finally {
                checkFutureStartJob()
                if (META.debuggable) {
                    val et = System.currentTimeMillis() - st
                    Log.d("A11yRuleEngine", "startQueryJob end $et ms")
                }
                querying = false
            }
        }
    }

    private fun checkFutureStartJob() {
        val t = System.currentTimeMillis()
        if (t - lastTriggerTime < 3000L || t - appChangeTime < 3000L) {
            scope.launch(actionDispatcher) {
                delay(300)
                startQueryJob()
            }
        } else if (activityRuleFlow.value.hasFeatureAction) {
            scope.launch(actionDispatcher) {
                delay(300)
                startQueryJob(byForced = true)
            }
        }
    }

    private fun fixAppId(rightAppId: String) {
        if (topActivityFlow.value.appId == rightAppId) return
        synchronized(topActivityFlow) {
            val topCpn = shizukuContextFlow.value.topCpn()
            if (topCpn?.packageName == rightAppId) {
                updateTopActivity(topCpn.packageName, topCpn.className)
            } else {
                updateTopActivity(rightAppId, null)
            }
        }
        scope.launch(actionDispatcher) {
            delay(300)
            startQueryJob()
        }
    }

    private suspend fun queryAction(
        byEvent: A11yEvent? = null,
        byForced: Boolean = false,
        delayRule: ResolvedRule? = null,
    ) {
        val tempStateEvent = latestStateEvent
        val newEvents = if (delayRule != null) {// 延迟规则不消耗事件
            null
        } else {
            synchronized(queryEvents) {
                if (byEvent != null && queryEvents.isEmpty()) {
                    return
                }
                (if (queryEvents.size > 1) {
                    val hasDiffItem = queryEvents.any { e ->
                        queryEvents.any { e2 -> !e.sameAs(e2) }
                    }
                    if (hasDiffItem) {
                        // 存在不同的事件节点, 全部丢弃使用 root 查询
                        null
                    } else {
                        // type,appId,className 一致, 需要在 synchronized 外验证是否是同一节点
                        arrayOf(
                            queryEvents[queryEvents.size - 2],
                            queryEvents.last(),
                        )
                    }
                } else if (queryEvents.size == 1) {
                    arrayOf(queryEvents.last())
                } else {
                    null
                }).apply {
                    queryEvents.clear()
                }
            }
        }
        val activityRule = synchronized(topActivityFlow) { activityRuleFlow.value }
        BypassPerfTrace.matcherStarted(topActivityFlow.value.appId, activityRule.priorityRules.size)
        activityRule.currentRules.forEach { rule ->
            if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID && !isBypassAppEnabled(topActivityFlow.value.appId)) {
                BypassDiagnostics.record(FailureReason.APP_DISABLED, detail = "bypass_app_opt_out")
                return@forEach
            }
            if (rule.status == RuleStatus.Status3 && rule.matchDelayJob.value == null) {
                rule.matchDelayJob.value = scope.launch(actionDispatcher) {
                    delay(rule.matchDelay)
                    rule.matchDelayJob.value = null
                    startQueryJob(byDelayRule = rule)
                }
            }
        }
        if (activityRule.skipMatch) {
            // 如果当前应用没有规则/暂停匹配, 则不去调用获取事件节点避免阻塞
            BypassDiagnostics.record(FailureReason.NO_RULE_FOR_APP, detail = "resolved_rules_not_runnable")
            return
        }
        var lastNode = if (newEvents == null || newEvents.size <= 1) {
            newEvents?.firstOrNull()?.safeSource
        } else {
            // 获取最后两个事件, 如果最后两个事件的节点不一致, 则丢弃
            // 相等则是同一个节点发出的连续事件, 常见于倒计时界面
            val lastNode = newEvents.last().safeSource
            if (lastNode == null || lastNode == newEvents[0].safeSource) {
                lastNode
            } else {
                null
            }
        }
        var lastNodeUsed = false
        if (!a11yContext.clearOldAppNodeCache()) {
            if (byEvent != null) { // 此为多数情况
                // 新事件到来时, 若缓存清理不及时会导致无法查询到节点
                a11yContext.clearNodeCache(lastNode)
            }
        }
        for (rule in activityRule.priorityRules) { // 规则数量有可能过多导致耗时过长
            if (!effective) return
            if (checkOutDate(activityRule, tempStateEvent)) break
            if (delayRule != null && delayRule !== rule) continue
            if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID && !isBypassAppEnabled(topActivityFlow.value.appId)) {
                BypassDiagnostics.record(FailureReason.APP_DISABLED, detail = "bypass_app_opt_out")
                continue
            }
            if (rule.status != RuleStatus.StatusOk) continue
            if (byForced && !rule.checkForced()) continue
            lastNode?.let { n ->
                val refreshOk = (!lastNodeUsed) || (try {
                    val e = n.refresh()
                    if (e) {
                        n.setGeneratedTime()
                    }
                    e
                } catch (_: Throwable) {
                    false
                })
                lastNodeUsed = true
                if (!refreshOk) {
                    lastNode = null
                }
            }
            val nodeVal = (lastNode ?: getTimeoutActiveWindow()) ?: run {
                BypassDiagnostics.record(FailureReason.ACCESSIBILITY_NODE_MISSING, detail = "active_window_unavailable")
                continue
            }
            val rightAppId = nodeVal.packageName?.toString() ?: break
            val matchApp = rule.matchActivity(rightAppId)
            if (topActivityFlow.value.appId != rightAppId || (!matchApp && rule is AppRule)) {
                scope.launch(eventDispatcher) { fixAppId(rightAppId) }
                return
            }
            if (!matchApp) {
                if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID) {
                    BypassDiagnostics.record(FailureReason.ACTIVITY_MISMATCH, detail = "rule_activity_mismatch")
                }
                continue
            }
            BypassPerfTrace.selectorQueried()
            val target = a11yContext.queryRule(rule, nodeVal) ?: run {
                if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID) {
                    BypassDiagnostics.record(FailureReason.SELECTOR_NO_MATCH, detail = "gkd_selector_no_match")
                    if (BypassTeachRules.isPendingVerification(rule.g.appId, rule.g.group.key)) {
                        // A first no-match is never a verification failure:
                        // only the armed verification window decides.
                        BypassTeachRules.noticeNoMatch(rule.g.appId, rule.g.group.key)
                    }
                }
                continue
            }
            BypassPerfTrace.matched(rule.statusText())
            val bypassContext = topActivityFlow.value
            val isBypassRule = rule.subsItem.id == BYPASS_SPLASH_SUBS_ID
            if (META.debuggable && isBypassRule) {
                Log.d("A11yRuleEngine", "bypass rule matched: ${rule.g.group.name}/${rule.rule.name} status=${rule.status}")
            }
            var bypassSessionId: String? = null
            var bypassMaxAttempts = 0
            if (isBypassRule) {
                val mode = BypassStrategyGate.currentStrategyMode()
                val rulePolicy = BypassRulePolicyResolver.resolve(rule)
                // A. Identity-derived minimum mode. Rule names are log
                // readability only; the boundary is trust + bypassMode.
                if (mode.ordinal < rulePolicy.minimumMode.ordinal) {
                    if (META.debuggable) {
                        Log.d("A11yRuleEngine", "bypass rule below minimum mode: ${rule.g.group.name}/${rule.rule.name} trust=${rulePolicy.trust}")
                    }
                    BypassDiagnostics.record(
                        FailureReason.GLOBAL_EXCLUDED,
                        packageName = bypassContext.appId,
                        activityName = bypassContext.activityId,
                        detail = "CLOSE_CANDIDATE_REJECTED reason=RULE_LEVEL trust=${rulePolicy.trust}",
                    )
                    continue
                }
                // B. Classify the matched control.
                val candidate = BypassExitClassifier.classifyNode(target)
                if (BypassExitClassifier.hasAdLabel(
                        target.text?.toString(),
                        target.contentDescription?.toString(),
                        target.viewIdResourceName,
                    )
                ) {
                    BypassAdContextTracker.noteAdLabel(bypassContext.appId, bypassContext.activityId)
                }
                val isDedicated = rulePolicy.trust == BypassRuleTrust.BUNDLED_DEDICATED
                if (!isDedicated && candidate == null) {
                    // A matched control with no exit semantics (or with
                    // negative/sensitive semantics) never acts.
                    BypassDiagnostics.record(
                        FailureReason.GLOBAL_EXCLUDED,
                        packageName = bypassContext.appId,
                        activityName = bypassContext.activityId,
                        detail = "CLOSE_CANDIDATE_REJECTED reason=NEGATIVE_SEMANTIC type=null",
                    )
                    BypassDetectionSessions.candidateRejected(
                        bypassContext.appId,
                        bypassContext.activityId,
                        "?",
                        BypassRejectReason.NEGATIVE_SEMANTIC,
                    )
                    continue
                }
                // C. Candidate gate: curated dedicated rules run un-gated;
                // every other candidate is gated per mode + trust + context.
                val reject = if (isDedicated) null else BypassStrategyGate.rejectReason(
                    candidate = candidate ?: BypassExitCandidateType.SKIP_TEXT,
                    packageName = bypassContext.appId,
                    activityName = bypassContext.activityId,
                    nodeWidth = target.casted.boundsInScreen.width(),
                    nodeHeight = target.casted.boundsInScreen.height(),
                    policy = mode.policy,
                    rulePolicy = rulePolicy,
                    contextLevel = BypassAdContextTracker.evaluate(bypassContext.appId, bypassContext.activityId),
                    inWindow = BypassStrategyGate.inStartupWindow(bypassContext.appId),
                )
                if (reject != null) {
                    if (META.debuggable) {
                        Log.d("A11yRuleEngine", "bypass candidate rejected: ${candidate?.name} reason=$reject")
                    }
                    BypassDiagnostics.record(
                        FailureReason.GLOBAL_EXCLUDED,
                        packageName = bypassContext.appId,
                        activityName = bypassContext.activityId,
                        detail = "CLOSE_CANDIDATE_REJECTED reason=$reject type=${candidate?.name ?: "?"}",
                    )
                    BypassDetectionSessions.candidateRejected(
                        bypassContext.appId,
                        bypassContext.activityId,
                        candidate?.name ?: "?",
                        reject,
                    )
                    continue
                }
                // D. One ad = one session: attach evidence to the window
                // session and keep the strategy recorded at session start.
                val sessionId = BypassDetectionSessions.ensureSession(
                    bypassContext.appId,
                    bypassContext.activityId,
                    mode,
                    rulePolicy.trust,
                ) ?: continue
                candidate?.let {
                    BypassAdContextTracker.noteCandidate(bypassContext.appId, bypassContext.activityId, it)
                    BypassDetectionSessions.noteCandidate(
                        sessionId,
                        bypassContext.appId,
                        bypassContext.activityId,
                        it,
                        rule.statusText(),
                        target,
                    )
                }
                BypassDetectionSessions.strategyApplied(bypassContext.appId, bypassContext.activityId, mode)
                // E. Action budget: reserve BEFORE acting so a failed action
                // still consumes an attempt (no infinite retry).
                bypassMaxAttempts = mode.policy.maxExitAttempts.coerceAtMost(3)
                if (!BypassActionBudget.reserveAttempt(sessionId, bypassMaxAttempts)) {
                    if (META.debuggable) {
                        Log.d("A11yRuleEngine", "bypass budget exhausted for session $sessionId")
                    }
                    BypassDiagnostics.record(
                        FailureReason.ACTION_NO_EFFECT,
                        packageName = bypassContext.appId,
                        activityName = bypassContext.activityId,
                        detail = "BUDGET_EXHAUSTED attempts=$bypassMaxAttempts",
                    )
                    BypassDetectionSessions.confirmedFailure(sessionId, FailureReason.ACTION_NO_EFFECT)
                    continue
                }
                bypassSessionId = sessionId
            }
            if (rule.checkDelay() && rule.actionDelayJob.value == null) {
                if (isBypassRule) {
                    BypassDiagnostics.record(FailureReason.WAITING_FOR_DELAY, detail = "rule_action_delay")
                    BypassDetectionSessions.waitingForDelay(bypassContext.appId, bypassContext.activityId)
                }
                rule.actionDelayJob.value = scope.launch(actionDispatcher) {
                    delay(rule.actionDelay)
                    rule.actionDelayJob.value = null
                    startQueryJob(byDelayRule = rule)
                }
                continue
            }
            if (rule.status != RuleStatus.StatusOk) break
            if (checkOutDate(activityRule, tempStateEvent)) break
            BypassPerfTrace.actionStarted(rule.statusText())
            if (isBypassRule && !target.isClickable) {
                BypassDiagnostics.record(FailureReason.TARGET_FOUND_NOT_CLICKABLE, detail = "matched_target_not_clickable")
                BypassDetectionSessions.targetNotClickable(bypassContext.appId, bypassContext.activityId)
            }
            val actionResult = rule.performAction(target)
            BypassPerfTrace.actionFinished(rule.statusText())
            BypassPerfTrace.actionFinishedT3(rule.statusText())
            bypassSessionId?.let { BypassDetectionSessions.actionAttempted(it, actionResult.action.toString()) }
            if (actionResult.result) {
                BypassPerfTrace.actionSucceeded()
                val topActivity = topActivityFlow.value
                rule.trigger()
                if (isBypassRule && bypassSessionId != null) {
                    val sid = bypassSessionId
                    // OutcomeVerifier is the only retry scheduler for bypass:
                    // Action -> fresh-state verify -> ad still there + budget ->
                    // fresh query for the next exit candidate.
                    scope.launch(actionDispatcher) {
                        val actionStart = System.currentTimeMillis()
                        val outcome = BypassOutcomeVerifier.verify(
                            rule = rule,
                            target = target,
                            packageName = bypassContext.appId,
                            activityName = bypassContext.activityId,
                            a11yContext = a11yContext,
                            freshWindowProvider = { getTimeoutActiveWindow() },
                            freshAdCandidateProvider = { root -> hasFreshAdCandidate(root) },
                        )
                        val latency = System.currentTimeMillis() - actionStart
                        BypassPerfTrace.outcomeConfirmed(rule.statusText(), outcome.name)
                        BypassDetectionSessions.outcomeConfirmed(sid, outcome, latency)
                        when (outcome) {
                            BypassOutcome.SUCCESS_CONFIRMED -> {
                                BypassTeachRules.markVerification(rule.g.appId, rule.g.group.key, success = true)
                                if (actionResult.action != ActionPerformer.None.action) {
                                    showActionToast(rule)
                                }
                            }
                            BypassOutcome.MISCLICK_SUSPECTED -> {
                                BypassDiagnostics.record(
                                    FailureReason.MISCLICK_SUSPECTED,
                                    packageName = bypassContext.appId,
                                    activityName = bypassContext.activityId,
                                    detail = "external_landing",
                                )
                            }
                            BypassOutcome.ACTION_NO_EFFECT,
                            BypassOutcome.UNRESOLVED,
                            -> {
                                if (BypassActionBudget.attemptsUsed(sid) < bypassMaxAttempts) {
                                    BypassDiagnostics.record(FailureReason.ACTION_NO_EFFECT, detail = "outcome=$outcome requery")
                                    startQueryJob(byForced = true)
                                } else {
                                    BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_NO_EFFECT)
                                }
                            }
                        }
                    }
                } else {
                    scope.launch(actionDispatcher) {
                        delay(300)
                        startQueryJob()
                    }
                    if (actionResult.action != ActionPerformer.None.action) {
                        showActionToast(rule)
                    }
                }
                addActionLog(rule, topActivity, target, actionResult)
            } else if (isBypassRule) {
                BypassDiagnostics.record(FailureReason.ACTION_FAILED, detail = "gkd_action_returned_false")
                bypassSessionId?.let { sid ->
                    if (BypassActionBudget.attemptsUsed(sid) < bypassMaxAttempts) {
                        startQueryJob(byForced = true)
                    } else {
                        BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_FAILED)
                    }
                }
            }
        }
    }

    private fun checkOutDate(
        activityRule: ActivityRule,
        stateEvent: A11yEvent?
    ): Boolean {
        if (stateEvent !== latestStateEvent) return true
        synchronized(topActivityFlow) {
            if (activityRule !== activityRuleFlow.value) return true
        }
        return false
    }

    /**
     * Whether any Bypass ad candidate still matches on a fresh root. Used by
     * the OutcomeVerifier: after an action, if any ad rule still matches, the
     * ad has not been closed yet. Reuses the GKD selector machinery (no
     * second scanner); bounded to keep the check cheap.
     */
    private fun hasFreshAdCandidate(root: AccessibilityNodeInfo): Boolean {
        val activityRule = synchronized(topActivityFlow) { activityRuleFlow.value }
        var queries = 0
        for (rule in activityRule.priorityRules) {
            if (rule.subsItem.id != BYPASS_SPLASH_SUBS_ID) continue
            if (queries++ >= 24) break
            val matched = runCatching { a11yContext.queryRule(rule, root) != null }.getOrDefault(false)
            if (matched) return true
        }
        return false
    }

    companion object {
        val service: A11yCommonImpl?
            get() = uiAutomationFlow.value?.takeIf {
                it.mode.value == latestServiceMode.value
            } ?: A11yService.instance
        val instance: A11yRuleEngine? get() = service?.ruleEngine

        fun compatWindows(): List<AccessibilityWindowInfo> {
            return try {
                service?.windowInfos
            } catch (_: Throwable) {
                null
            } ?: emptyList()
        }

        fun onScreenForcedActive() {
            instance?.onScreenForcedActive()
        }

        fun performActionBack(): Boolean {
            val r1 = shizukuContextFlow.value.inputManager?.key(KeyEvent.KEYCODE_BACK)
            if (r1 != null) return true
            return A11yService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
        }

        suspend fun screenshot(): Bitmap? = service?.screenshot()

        suspend fun execAction(gkdAction: GkdAction): ActionResult {
            val selector = Selector.parseOrNull(gkdAction.selector) ?: throw RpcError("非法选择器")
            runCatching { selector.checkType(typeInfo) }.exceptionOrNull()?.let {
                throw RpcError("选择器类型错误:${it.message}")
            }
            val s = instance ?: throw RpcError("服务未连接")
            val a = s.safeActiveWindow ?: throw RpcError("界面没有节点信息")
            val targetNode = A11yContext(s, interruptable = false).querySelfOrSelector(
                a, selector, MatchOption(fastQuery = gkdAction.fastQuery)
            ) ?: throw RpcError("没有查询到节点")
            return withContext(Dispatchers.IO) {
                ActionPerformer
                    .getAction(gkdAction.action ?: ActionPerformer.None.action)
                    .perform(targetNode, gkdAction)
            }
        }

    }
}
