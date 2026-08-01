package dev.subnetory.web.form;

/** Phase 7 audit, 31/07/2026 — liaison du formulaire admin/backup.html. */
public class BackupSettingsForm {

    private boolean enabled;
    private String cronExpression;
    private int retentionCount;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public int getRetentionCount() { return retentionCount; }
    public void setRetentionCount(int retentionCount) { this.retentionCount = retentionCount; }
}
