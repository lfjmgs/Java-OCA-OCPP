package eu.chargetime.ocpp.model;

public class DisconnectionInformation {
    private int code;
    private String reason;
    private boolean remote;

    public DisconnectionInformation(int code, boolean remote, String reason) {
        this.code = code;
        this.remote = remote;
        this.reason = reason;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public boolean isRemote() {
        return remote;
    }

    public void setRemote(boolean remote) {
        this.remote = remote;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
