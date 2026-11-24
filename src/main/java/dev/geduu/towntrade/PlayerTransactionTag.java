package dev.geduu.towntrade;

public interface PlayerTransactionTag {
    String KEY = "TownTradeTransaction";

    String towntrade$transaction();
    boolean towntrade$permitFlight();
    void towntrade$setPermitFlight(boolean owned);
    boolean towntrade$restoredFlying();
    void towntrade$setRestoredFlying(boolean flying);
    long towntrade$landing();
    void towntrade$setLanding(long until);
    void towntrade$setTransaction(String transaction);
}
