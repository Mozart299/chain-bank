package dev.chainbank.api.transfer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/** Releases funds held by transfers that were never approved within their window. */
@ApplicationScoped
public class ExpiryJob {

    private static final Logger LOG = Logger.getLogger(ExpiryJob.class);

    @Inject
    TransferService transfers;

    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        int n = transfers.expireDue();
        if (n > 0) {
            LOG.infof("Expired %d unapproved transfer(s) and released their reserved funds", n);
        }
    }
}
