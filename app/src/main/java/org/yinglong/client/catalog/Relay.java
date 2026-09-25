package org.yinglong.client.catalog;

public final class Relay {
    public final String hostName;
    public final String ip;
    public final long score;
    public final int pingMs;
    public final long speedBps;
    public final String countryLong;
    public final String countryShort;
    public final int sessions;
    public final long uptimeMs;
    public final long totalUsers;
    public final long totalTraffic;
    public final String logType;
    public final String operator;
    public final String message;
    public final String openVpnConfigBase64;

    public Relay(String hostName, String ip, long score, int pingMs, long speedBps,
                 String countryLong, String countryShort, int sessions, long uptimeMs,
                 long totalUsers, long totalTraffic, String logType, String operator,
                 String message, String openVpnConfigBase64) {
        this.hostName = hostName;
        this.ip = ip;
        this.score = score;
        this.pingMs = pingMs;
        this.speedBps = speedBps;
        this.countryLong = countryLong;
        this.countryShort = countryShort;
        this.sessions = sessions;
        this.uptimeMs = uptimeMs;
        this.totalUsers = totalUsers;
        this.totalTraffic = totalTraffic;
        this.logType = logType;
        this.operator = operator;
        this.message = message;
        this.openVpnConfigBase64 = openVpnConfigBase64;
    }

    public double speedMbps() {
        return speedBps / 1_000_000.0;
    }
}
