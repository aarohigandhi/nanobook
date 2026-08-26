package io.nanobook.bench;

import io.nanobook.book.OrderBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A pre-generated, self-contained order-lifecycle workload.
 *
 * <p>Stored as parallel primitive arrays and replayed by index, so replaying it
 * costs nothing beyond the book operations themselves. A script that allocated
 * or branched through a collection would be measuring itself.
 *
 * <p>The script is <b>balanced</b>: every order added is deleted before the end,
 * so the book returns to empty and a second replay is identical to the first.
 * Without that, each JMH iteration would run against a differently-shaped book
 * and the numbers would drift for reasons that have nothing to do with the code.
 *
 * <p>The message mix mirrors a real ITCH session, where order lifecycle traffic
 * dwarfs executions. Benchmarking a matching-heavy mix would be measuring the
 * wrong path.
 */
public final class MessageScript {

    public static final byte ADD = 0;
    public static final byte DELETE = 1;
    public static final byte CANCEL = 2;
    public static final byte EXECUTE = 3;

    /** Penny ticks, matching the book default. */
    private static final int TICK = 100;

    public final byte[] kind;
    public final long[] reference;
    public final int[] price;
    public final int[] shares;
    public final boolean[] buy;
    public final int length;

    private MessageScript(int length) {
        this.length = length;
        this.kind = new byte[length];
        this.reference = new long[length];
        this.price = new int[length];
        this.shares = new int[length];
        this.buy = new boolean[length];
    }

    private record Live(long reference, int shares) {}

    /**
     * Builds a script of roughly {@code target} operations. The result is
     * slightly longer, since it appends the deletes needed to drain the book.
     */
    public static MessageScript generate(long seed, int target) {
        Random random = new Random(seed);

        List<Byte> kinds = new ArrayList<>(target);
        List<Long> references = new ArrayList<>(target);
        List<Integer> prices = new ArrayList<>(target);
        List<Integer> quantities = new ArrayList<>(target);
        List<Boolean> sides = new ArrayList<>(target);
        List<Live> live = new ArrayList<>();
        long nextReference = 1;

        // Exactly `target` messages, drain included. Each iteration emits one
        // message; an ADD also adds one order that the drain must later delete,
        // so an ADD costs two of the budget and is only legal while two remain.
        // Without this the script overran `target` by the length of its drain
        // tail, and @OperationsPerInvocation -- a compile-time constant -- was
        // dividing by a message count the script never actually had.
        while (kinds.size() + live.size() < target) {
            int roll = random.nextInt(100);
            int remaining = target - (kinds.size() + live.size());
            boolean canAdd = remaining >= 2;

            // With one slot left, only a partial reduction fits. An ADD needs
            // two (itself plus its drain delete), and a DELETE is budget-neutral
            // -- it emits a message but retires one the drain owed, so the
            // budget never moves and the loop spins until `live` empties. That
            // spin was the off-by-one: it exited one message short.
            if (remaining == 1) {
                int index = indexOfDivisible(live);
                if (index < 0) break;
                Live order = live.get(index);
                int reduction = order.shares() - 1;
                live.set(index, new Live(order.reference(), 1));
                append(kinds, references, prices, quantities, sides,
                        CANCEL, order.reference(), 0, reduction);
                continue;
            }

            if (canAdd && (live.isEmpty() || roll < 52)) {
                long ref = nextReference++;
                int qty = 100 * (1 + random.nextInt(20));
                boolean side = random.nextBoolean();
                int px = (side ? 1_000 + random.nextInt(100) : 1_090 + random.nextInt(100)) * TICK;

                kinds.add(ADD);
                references.add(ref);
                prices.add(px);
                quantities.add(qty);
                sides.add(side);
                live.add(new Live(ref, qty));
            } else if (roll < 82 || !canAdd) {
                Live order = live.remove(random.nextInt(live.size()));
                append(kinds, references, prices, quantities, sides,
                        DELETE, order.reference(), 0, 0);
            } else if (roll < 93) {
                int index = random.nextInt(live.size());
                Live order = live.get(index);
                int reduction = Math.max(1, order.shares() / 4);
                if (reduction >= order.shares()) {
                    live.remove(index);
                } else {
                    live.set(index, new Live(order.reference(), order.shares() - reduction));
                }
                append(kinds, references, prices, quantities, sides,
                        CANCEL, order.reference(), 0, reduction);
            } else {
                int index = random.nextInt(live.size());
                Live order = live.get(index);
                int filled = Math.max(1, order.shares() / 2);
                if (filled >= order.shares()) {
                    live.remove(index);
                } else {
                    live.set(index, new Live(order.reference(), order.shares() - filled));
                }
                append(kinds, references, prices, quantities, sides,
                        EXECUTE, order.reference(), 0, filled);
            }
        }

        // Drain, so the script leaves the book exactly as it found it.
        for (Live order : live) {
            append(kinds, references, prices, quantities, sides,
                    DELETE, order.reference(), 0, 0);
        }

        MessageScript script = new MessageScript(kinds.size());
        for (int i = 0; i < script.length; i++) {
            script.kind[i] = kinds.get(i);
            script.reference[i] = references.get(i);
            script.price[i] = prices.get(i);
            script.shares[i] = quantities.get(i);
            script.buy[i] = sides.get(i);
        }
        return script;
    }

    /** First live order that can be partially reduced, or -1 if none can. */
    private static int indexOfDivisible(List<Live> live) {
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).shares() >= 2) return i;
        }
        return -1;
    }

    private static void append(List<Byte> kinds, List<Long> references, List<Integer> prices,
                               List<Integer> quantities, List<Boolean> sides,
                               byte kind, long reference, int price, int shares) {
        kinds.add(kind);
        references.add(reference);
        prices.add(price);
        quantities.add(shares);
        sides.add(false);
    }

    /** Applies one operation to a book. */
    public void applyTo(OrderBook book, int index) {
        switch (kind[index]) {
            case ADD -> book.onAddOrder(0L, 0, reference[index], buy[index],
                    shares[index], 0, price[index]);
            case DELETE -> book.onOrderDelete(0L, 0, reference[index]);
            case CANCEL -> book.onOrderCancel(0L, 0, reference[index], shares[index]);
            case EXECUTE -> book.onOrderExecuted(0L, 0, reference[index], shares[index], 0L);
            default -> throw new IllegalStateException("unknown kind " + kind[index]);
        }
    }

    /** Replays the whole script. */
    public void replay(OrderBook book) {
        for (int i = 0; i < length; i++) {
            applyTo(book, i);
        }
    }
}
