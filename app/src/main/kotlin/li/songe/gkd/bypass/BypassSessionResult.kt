package li.songe.gkd.bypass

/**
 * Product outcome of one ad session. The GKD ActionLog keeps meaning
 * "an action was performed"; the session result is the only source of
 * truth for "an ad was actually closed".
 */
enum class BypassSessionResult {
    /** Session open / in progress. */
    OPEN,

    /** OutcomeVerifier confirmed the exit candidate is gone and the ad
     *  context no longer holds (or an explicit dedicated-rule result). */
    SUCCESS_CONFIRMED,

    /** The ad remained after the action budget was exhausted. */
    FAILURE_CONFIRMED,

    /** It was not possible to determine whether the ad actually closed. */
    UNRESOLVED,

    /** The action threw the user onto an external landing (browser, app
     *  store, ad landing page); the session stops immediately. */
    MISCLICK_SUSPECTED,
}
