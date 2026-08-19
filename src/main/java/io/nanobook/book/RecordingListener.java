package io.nanobook.book;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures an execution report stream as plain text.
 *
 * <p>Text rather than objects on purpose: comparing two streams should report
 * <i>what</i> differed, not just that something did, and a failing assertion on
 * two lists of strings prints a usable diff. It also forces every field into
 * the comparison — a record with a forgotten {@code equals} silently ignores
 * whatever it left out.
 *
 * <p>Allocates freely. This is a test and diagnostic tool, never on a hot path.
 */
public final class RecordingListener implements ExecutionListener {

    private final List<String> reports = new ArrayList<>();

    public List<String> reports() {
        return reports;
    }

    public void clear() {
        reports.clear();
    }

    @Override
    public void onAccepted(long orderId, boolean buy, int price, int shares) {
        reports.add("accept " + orderId + " " + side(buy) + " " + price + " " + shares);
    }

    @Override
    public void onRejected(long orderId, RejectReason reason) {
        reports.add("reject " + orderId + " " + reason);
    }

    @Override
    public void onFill(long makerOrderId, long takerOrderId, int price, int shares, boolean takerBuy) {
        reports.add("fill maker=" + makerOrderId + " taker=" + takerOrderId
                + " " + price + " " + shares + " " + side(takerBuy));
    }

    @Override
    public void onResting(long orderId, boolean buy, int price, int shares) {
        reports.add("rest " + orderId + " " + side(buy) + " " + price + " " + shares);
    }

    @Override
    public void onCancelled(long orderId, int remainingShares) {
        reports.add("cancel " + orderId + " " + remainingShares);
    }

    @Override
    public void onExpired(long orderId, int remainingShares) {
        reports.add("expire " + orderId + " " + remainingShares);
    }

    private static String side(boolean buy) {
        return buy ? "B" : "S";
    }
}
