package net.junsuzu.syncedNet;

public class TransferRequest {
    String targetServer;
    String targetGate;
    TransferRequest(String targetServer, String targetGate) {
        this.targetServer = targetServer;
        this.targetGate = targetGate;
    }
}