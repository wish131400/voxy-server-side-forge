package dev.xantha.vss.networking.client;

final class FarVehicleInitializationGate {
    enum State {
        WAITING_FULL_DATA,
        INITIALIZING,
        READY,
        INVALID
    }

    private State state = State.WAITING_FULL_DATA;

    State state() {
        return state;
    }

    boolean begin(boolean fullData, boolean hasInitializationData) {
        if (!fullData || !hasInitializationData) {
            return false;
        }
        state = State.INITIALIZING;
        return true;
    }

    void markReady() {
        state = State.READY;
    }

    void markInvalid() {
        state = State.INVALID;
    }

    void reset() {
        state = State.WAITING_FULL_DATA;
    }

    boolean isReady() {
        return state == State.READY;
    }
}
