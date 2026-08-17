package ru.repethelper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdminDeletionWorker {
    private final AdminConsoleService admins;
    public AdminDeletionWorker(AdminConsoleService admins) { this.admins = admins; }
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purgeDueUsers() { admins.expireRestrictions(); admins.purgeDueUsers(); }
}
