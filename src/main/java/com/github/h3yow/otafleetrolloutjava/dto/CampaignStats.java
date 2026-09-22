package com.github.h3yow.otafleetrolloutjava.dto;

/** 活动维度任务计数：dispatched+success+rolledBack+failed 应等于设备总数 */
public class CampaignStats {
    private int total;
    private int dispatched;
    private int success;
    private int rolledBack;
    private int failed;
    private int attempts;
    private double failureRate; // (rolledBack+failed)/settled，settled=0 时为 0

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getDispatched() { return dispatched; }
    public void setDispatched(int dispatched) { this.dispatched = dispatched; }
    public int getSuccess() { return success; }
    public void setSuccess(int success) { this.success = success; }
    public int getRolledBack() { return rolledBack; }
    public void setRolledBack(int rolledBack) { this.rolledBack = rolledBack; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public double getFailureRate() { return failureRate; }
    public void setFailureRate(double failureRate) { this.failureRate = failureRate; }
}
