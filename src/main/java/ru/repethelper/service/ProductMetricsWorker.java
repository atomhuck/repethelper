package ru.repethelper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProductMetricsWorker {
    private final AdminConsoleService admins;
    public ProductMetricsWorker(AdminConsoleService admins) { this.admins = admins; }
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sampleOnline() { admins.sampleOnline(); }
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 180_000)
    public void cleanup() { admins.cleanupMetrics(); }
}
