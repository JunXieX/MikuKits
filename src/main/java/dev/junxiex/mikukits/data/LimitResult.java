package dev.junxiex.mikukits.data;

/**
 * 领取校验结果。
 */
public enum LimitResult {

    OK,
    NO_PERMISSION,
    COOLDOWN,
    MAX_CLAIMS,
    PERIOD_EXCEEDED,
    DATA_LOADING,
    /** 奖励发放全部失败，未消耗领取次数。 */
    FAILED,
    CANCELLED;

    public boolean allowed() {
        return this == OK;
    }
}
