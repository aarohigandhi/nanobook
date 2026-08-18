package io.nanobook.itch;

/**
 * Fans one message stream out to several handlers, in order.
 *
 * <p>Iterates a fixed array rather than a collection so the loop stays
 * allocation-free and the JIT can unroll it. Handlers are called in the order
 * given, which matters: a handler that resolves symbol ids should run before
 * one that consumes them.
 */
public final class CompositeHandler implements ItchHandler {

    private final ItchHandler[] handlers;

    public CompositeHandler(ItchHandler... handlers) {
        this.handlers = handlers.clone();
    }

    @Override
    public void onSystemEvent(long timestamp, char eventCode) {
        for (ItchHandler handler : handlers) handler.onSystemEvent(timestamp, eventCode);
    }

    @Override
    public void onStockDirectory(long timestamp, int locate, int symbolId, int roundLotSize) {
        for (ItchHandler handler : handlers) handler.onStockDirectory(timestamp, locate, symbolId, roundLotSize);
    }

    @Override
    public void onTradingAction(long timestamp, int locate, char tradingState) {
        for (ItchHandler handler : handlers) handler.onTradingAction(timestamp, locate, tradingState);
    }

    @Override
    public void onAddOrder(long timestamp, int locate, long orderRef,
                           boolean buy, int shares, int symbolId, int price) {
        for (ItchHandler handler : handlers) handler.onAddOrder(timestamp, locate, orderRef, buy, shares, symbolId, price);
    }

    @Override
    public void onOrderExecuted(long timestamp, int locate, long orderRef, int shares, long matchNumber) {
        for (ItchHandler handler : handlers) handler.onOrderExecuted(timestamp, locate, orderRef, shares, matchNumber);
    }

    @Override
    public void onOrderExecutedWithPrice(long timestamp, int locate, long orderRef, int shares,
                                         long matchNumber, boolean printable, int price) {
        for (ItchHandler handler : handlers) handler.onOrderExecutedWithPrice(timestamp, locate, orderRef, shares, matchNumber, printable, price);
    }

    @Override
    public void onOrderCancel(long timestamp, int locate, long orderRef, int shares) {
        for (ItchHandler handler : handlers) handler.onOrderCancel(timestamp, locate, orderRef, shares);
    }

    @Override
    public void onOrderDelete(long timestamp, int locate, long orderRef) {
        for (ItchHandler handler : handlers) handler.onOrderDelete(timestamp, locate, orderRef);
    }

    @Override
    public void onOrderReplace(long timestamp, int locate, long originalRef,
                               long newRef, int shares, int price) {
        for (ItchHandler handler : handlers) handler.onOrderReplace(timestamp, locate, originalRef, newRef, shares, price);
    }

    @Override
    public void onTrade(long timestamp, int locate, long orderRef, boolean buy,
                        int shares, int symbolId, int price, long matchNumber) {
        for (ItchHandler handler : handlers) handler.onTrade(timestamp, locate, orderRef, buy, shares, symbolId, price, matchNumber);
    }

    @Override
    public void onCrossTrade(long timestamp, int locate, long shares, int symbolId,
                             int price, long matchNumber, char crossType) {
        for (ItchHandler handler : handlers) handler.onCrossTrade(timestamp, locate, shares, symbolId, price, matchNumber, crossType);
    }

    @Override
    public void onBrokenTrade(long timestamp, int locate, long matchNumber) {
        for (ItchHandler handler : handlers) handler.onBrokenTrade(timestamp, locate, matchNumber);
    }

    @Override
    public void onOther(long timestamp, int locate, byte messageType) {
        for (ItchHandler handler : handlers) handler.onOther(timestamp, locate, messageType);
    }
}
