package app.bypassads.core.actuation

/** Concrete reason why the fail-closed gate blocked actuation (ADR-0002 §3). */
enum class ActuationBlockReason {
    /** Action mode not armed (OFF/SHADOW never allow). */
    MODE_BLOCKED,

    /** The case ended or was superseded since the proposal. */
    CASE_STALE,

    /** The scan generation moved on (scan busy / stale generation). */
    STALE_GENERATION,

    /** Re-resolved package differs from the proposal package. */
    PACKAGE_CHANGED,

    /** Re-resolved window context differs from the proposal window. */
    WINDOW_CHANGED,

    /** Zero matching targets in the fresh tree. */
    TARGET_DISAPPEARED,

    /** More than one matching target in the fresh tree. */
    MULTIPLE_TARGETS,

    /** The explicit label is no longer present/identical. */
    LABEL_CHANGED,

    /** Identity cannot be confirmed (resourceId mismatch, ambiguous state). */
    IDENTITY_AMBIGUOUS,

    /** Geometry drifted outside the allowed zone (position/central drift). */
    GEOMETRY_CHANGED,

    /** Target grew beyond the small-target bound. */
    LARGE_TARGET,

    /** Target is no longer clickable / enabled / visible. */
    NOT_CLICKABLE,

    /** A large clickable ancestor makes the tap region unsafe. */
    ANCESTOR_RISK,

    /** A CTA sibling (立即体验 / 立即打开 / 下载 / 查看详情 …) is present. */
    CTA_RISK,

    /** Revalidated score dropped below the click threshold. */
    SCORE_DROPPED,

    /** Revalidated risks fell to or below the severe floor. */
    RISK_BLOCKED,

    /** Countdown behaved abnormally on revalidation. */
    COUNTDOWN_ANOMALY,

    /** This case already had its single allowed actuation attempt. */
    ATTEMPT_LIMIT_REACHED,

    /** The fresh snapshot is not newer than the proposal. */
    REVALIDATION_STALE,
}
