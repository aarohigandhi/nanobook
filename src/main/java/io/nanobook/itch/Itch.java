package io.nanobook.itch;

/**
 * Nasdaq TotalView-ITCH 5.0 wire format constants.
 *
 * <p>Every message shares an 11-byte header:
 * <pre>
 *   offset  0  ( 1)  message type       char
 *   offset  1  ( 2)  stock locate       uint16
 *   offset  3  ( 2)  tracking number    uint16
 *   offset  5  ( 6)  timestamp          uint48, nanoseconds since midnight ET
 * </pre>
 *
 * <p>In the downloadable file format each message is preceded by a 2-byte
 * big-endian length prefix, which is NOT part of the message itself.
 *
 * <p>All multi-byte integers are big-endian and <b>unsigned</b>. Prices are
 * uint32 with 4 implied decimal places, so 1234500 means $123.4500. Keep prices
 * as ints in these raw units on the hot path -- never convert to double.
 */
public final class Itch {

    private Itch() {}

    /** Length prefix that precedes each message in the file format. */
    public static final int LENGTH_PREFIX_BYTES = 2;

    /** Shared header size: type + locate + tracking + timestamp. */
    public static final int HEADER_BYTES = 11;

    /** Price fields carry 4 implied decimals. */
    public static final int PRICE_SCALE = 10_000;

    // ---------------------------------------------------------------
    // Message types
    // ---------------------------------------------------------------

    public static final byte SYSTEM_EVENT              = 'S';
    public static final byte STOCK_DIRECTORY           = 'R';
    public static final byte STOCK_TRADING_ACTION      = 'H';
    public static final byte REG_SHO_RESTRICTION       = 'Y';
    public static final byte MARKET_PARTICIPANT_POS    = 'L';
    public static final byte MWCB_DECLINE_LEVEL        = 'V';
    public static final byte MWCB_STATUS               = 'W';
    public static final byte IPO_QUOTING_PERIOD        = 'K';
    public static final byte LULD_AUCTION_COLLAR       = 'J';
    public static final byte OPERATIONAL_HALT          = 'h';
    public static final byte ADD_ORDER                 = 'A';
    public static final byte ADD_ORDER_MPID            = 'F';
    public static final byte ORDER_EXECUTED            = 'E';
    public static final byte ORDER_EXECUTED_WITH_PRICE = 'C';
    public static final byte ORDER_CANCEL              = 'X';
    public static final byte ORDER_DELETE              = 'D';
    public static final byte ORDER_REPLACE             = 'U';
    public static final byte TRADE                     = 'P';
    public static final byte CROSS_TRADE               = 'Q';
    public static final byte BROKEN_TRADE              = 'B';
    public static final byte NOII                      = 'I';

    // ---------------------------------------------------------------
    // Message lengths, indexed by message-type byte.
    // A flat 128-entry table beats a switch: one array load, no branches.
    // Zero means "unknown type" -- treat that as a fatal stream error.
    // ---------------------------------------------------------------

    private static final int[] LENGTHS = new int[128];

    static {
        LENGTHS[SYSTEM_EVENT]              = 12;
        LENGTHS[STOCK_DIRECTORY]           = 39;
        LENGTHS[STOCK_TRADING_ACTION]      = 25;
        LENGTHS[REG_SHO_RESTRICTION]       = 20;
        LENGTHS[MARKET_PARTICIPANT_POS]    = 26;
        LENGTHS[MWCB_DECLINE_LEVEL]        = 35;
        LENGTHS[MWCB_STATUS]               = 12;
        LENGTHS[IPO_QUOTING_PERIOD]        = 28;
        LENGTHS[LULD_AUCTION_COLLAR]       = 35;
        LENGTHS[OPERATIONAL_HALT]          = 21;
        LENGTHS[ADD_ORDER]                 = 36;
        LENGTHS[ADD_ORDER_MPID]            = 40;
        LENGTHS[ORDER_EXECUTED]            = 31;
        LENGTHS[ORDER_EXECUTED_WITH_PRICE] = 36;
        LENGTHS[ORDER_CANCEL]              = 23;
        LENGTHS[ORDER_DELETE]              = 19;
        LENGTHS[ORDER_REPLACE]             = 35;
        LENGTHS[TRADE]                     = 44;
        LENGTHS[CROSS_TRADE]               = 40;
        LENGTHS[BROKEN_TRADE]              = 19;
        LENGTHS[NOII]                      = 50;
    }

    /** Body length in bytes for a message type, or 0 if the type is unknown. */
    public static int messageLength(byte type) {
        int t = type & 0xFF;
        return t < 128 ? LENGTHS[t] : 0;
    }

    /** Human-readable name for a message type. Reporting only -- allocates. */
    public static String typeName(byte type) {
        return switch (type) {
            case SYSTEM_EVENT              -> "SystemEvent";
            case STOCK_DIRECTORY           -> "StockDirectory";
            case STOCK_TRADING_ACTION      -> "StockTradingAction";
            case REG_SHO_RESTRICTION       -> "RegSHORestriction";
            case MARKET_PARTICIPANT_POS    -> "MarketParticipantPosition";
            case MWCB_DECLINE_LEVEL        -> "MwcbDeclineLevel";
            case MWCB_STATUS               -> "MwcbStatus";
            case IPO_QUOTING_PERIOD        -> "IpoQuotingPeriodUpdate";
            case LULD_AUCTION_COLLAR       -> "LuldAuctionCollar";
            case OPERATIONAL_HALT          -> "OperationalHalt";
            case ADD_ORDER                 -> "AddOrder";
            case ADD_ORDER_MPID            -> "AddOrderWithMPID";
            case ORDER_EXECUTED            -> "OrderExecuted";
            case ORDER_EXECUTED_WITH_PRICE -> "OrderExecutedWithPrice";
            case ORDER_CANCEL              -> "OrderCancel";
            case ORDER_DELETE              -> "OrderDelete";
            case ORDER_REPLACE             -> "OrderReplace";
            case TRADE                     -> "Trade";
            case CROSS_TRADE               -> "CrossTrade";
            case BROKEN_TRADE              -> "BrokenTrade";
            case NOII                      -> "NetOrderImbalanceIndicator";
            default                        -> "Unknown";
        };
    }

    // ---------------------------------------------------------------
    // Field offsets, relative to the start of the message body.
    // Transcribed from the TotalView-ITCH 5.0 specification.
    // ---------------------------------------------------------------

    /** Offsets shared by every message. */
    public static final int OFF_TYPE            = 0;
    public static final int OFF_STOCK_LOCATE    = 1;
    public static final int OFF_TRACKING_NUMBER = 3;
    public static final int OFF_TIMESTAMP       = 5;

    /** Add Order (A, 36 bytes) and Add Order with MPID (F, 40 bytes). */
    public static final int ADD_ORDER_REF      = 11;
    public static final int ADD_BUY_SELL       = 19;
    public static final int ADD_SHARES         = 20;
    public static final int ADD_STOCK          = 24;
    public static final int ADD_PRICE          = 32;
    public static final int ADD_ATTRIBUTION    = 36; // F only

    /** Order Executed (E, 31 bytes). */
    public static final int EXEC_ORDER_REF     = 11;
    public static final int EXEC_SHARES        = 19;
    public static final int EXEC_MATCH_NUMBER  = 23;

    /** Order Executed With Price (C, 36 bytes). */
    public static final int EXECP_ORDER_REF    = 11;
    public static final int EXECP_SHARES       = 19;
    public static final int EXECP_MATCH_NUMBER = 23;
    public static final int EXECP_PRINTABLE    = 31;
    public static final int EXECP_PRICE        = 32;

    /** Order Cancel (X, 23 bytes) -- partial cancel, reduces displayed shares. */
    public static final int CANCEL_ORDER_REF   = 11;
    public static final int CANCEL_SHARES      = 19;

    /** Order Delete (D, 19 bytes) -- full removal. */
    public static final int DELETE_ORDER_REF   = 11;

    /**
     * Order Replace (U, 35 bytes). The original order is removed and a new one
     * is added under a new reference. The replacement goes to the BACK of the
     * queue at its price level -- it does not inherit priority. Getting that
     * wrong silently corrupts every queue-position result downstream.
     */
    public static final int REPLACE_ORIG_REF   = 11;
    public static final int REPLACE_NEW_REF    = 19;
    public static final int REPLACE_SHARES     = 27;
    public static final int REPLACE_PRICE      = 31;

    /**
     * Trade, non-cross (P, 44 bytes). Represents hidden liquidity executing.
     * It does NOT modify the visible book -- consume it for trade prints only.
     */
    public static final int TRADE_ORDER_REF    = 11;
    public static final int TRADE_BUY_SELL     = 19;
    public static final int TRADE_SHARES       = 20;
    public static final int TRADE_STOCK        = 24;
    public static final int TRADE_PRICE        = 32;
    public static final int TRADE_MATCH_NUMBER = 36;

    /** Cross Trade (Q, 40 bytes). Note: shares is uint64 here, not uint32. */
    public static final int CROSS_SHARES       = 11;
    public static final int CROSS_STOCK        = 19;
    public static final int CROSS_PRICE        = 27;
    public static final int CROSS_MATCH_NUMBER = 31;
    public static final int CROSS_TYPE         = 39;

    /** Broken Trade (B, 19 bytes). */
    public static final int BROKEN_MATCH_NUMBER = 11;

    /** System Event (S, 12 bytes). */
    public static final int SYSTEM_EVENT_CODE  = 11;

    /** Stock Directory (R, 39 bytes) -- maps stock locate to ticker symbol. */
    public static final int DIR_STOCK           = 11;
    public static final int DIR_MARKET_CATEGORY = 19;
    public static final int DIR_ROUND_LOT_SIZE  = 21;

    /** Stock Trading Action (H, 25 bytes). */
    public static final int ACTION_STOCK         = 11;
    public static final int ACTION_TRADING_STATE = 19;

    /** Ticker symbols are 8 bytes of right-padded ASCII -- exactly one long. */
    public static final int STOCK_FIELD_BYTES = 8;
}
