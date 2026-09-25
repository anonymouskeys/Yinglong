import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayCsv;
import java.io.StringReader;
import java.util.List;

public class RelayCsvSmokeTest {
    public static void main(String[] args) throws Exception {
        String csv = "*vpn_servers\n" +
                "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n" +
                "node1,203.0.113.7,123,20,100000000,Japan,JP,4,1000,50,999,2weeks,op,msg,Q09ORklH\n*\n";
        List<Relay> rows = RelayCsv.parse(new StringReader(csv));
        if (rows.size() != 1) throw new AssertionError("expected 1 relay");
        Relay r = rows.get(0);
        if (!"203.0.113.7".equals(r.ip) || r.score != 123 || r.pingMs != 20) {
            throw new AssertionError("parsed relay mismatch");
        }
        System.out.println("RelayCsv smoke test OK");
    }
}
