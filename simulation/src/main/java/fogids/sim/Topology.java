package fogids.sim;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * Builds the four-layer fog topology described in the architecture report,
 * Section 3 ("Final system architecture") and Section 6 ("Worked example").
 *
 * Layout:
 *   cloud
 *     └── proxy (rerouting / gateway authority; also the parent for all fog nodes)
 *           ├── fog-1  (6 roadside sensor/vehicle pairs)
 *           ├── fog-2  (15 roadside sensor/vehicle pairs — the corridor segment
 *           │           the Section 6 attack scenario targets)
 *           └── fog-3  (6 roadside sensor/vehicle pairs)
 *
 * MODELLING ASSUMPTION (stated explicitly, see docs/interfaces.md):
 * each roadside sensor/vehicle pair is wired directly to its serving fog node,
 * not via the proxy. The report's Section 4 diagram shows the ECU's decision
 * depending on fog data that is "advisory, not authoritative" and arriving
 * within a bounded budget; the proxy's job in this design is coordination,
 * analytics relay, and — once Member C's quarantine logic lands — rerouting a
 * zone's coverage to a healthy peer. It does not sit on the real-time path
 * between a sensor and its own fog node in normal operation.
 */
public class Topology {

    public static final int FOG_1_PAIRS = 6;
    public static final int FOG_2_PAIRS = 15; // matches the Section 6 worked example
    public static final int FOG_3_PAIRS = 6;

    /** Round-trip fog-node <-> sensor/vehicle latency, ms. Represents the roadside V2I hop. */
    public static final double EDGE_LATENCY_MS = 2.0;
    /** Fog node <-> proxy latency, ms. */
    public static final double FOG_TO_PROXY_LATENCY_MS = 8.0;
    /** Proxy <-> cloud latency, ms. Represents the core network / backhaul. */
    public static final double PROXY_TO_CLOUD_LATENCY_MS = 50.0;
    /** Sensor inter-transmission period, ms — roughly a 20 Hz V2X telemetry rate. */
    public static final double SENSOR_PERIOD_MS = 50.0;

    public final List<FogDevice> fogDevices = new ArrayList<>();
    public final List<Sensor> sensors = new ArrayList<>();
    public final List<Actuator> actuators = new ArrayList<>();

    public FogDevice cloud;
    public FogDevice proxy;
    public FogDevice fog1, fog2, fog3;

    /**
     * @param userId       broker id, needed to attach sensors/actuators to the right app/user
     * @param appId        application id
     * @param edgeCompute  true if fog nodes should run local processing (fog scenarios);
     *                     false pins everything conceptually to the cloud (cloud-only baseline
     *                     still needs the fog *devices* to exist as pass-through routers, since
     *                     sensors must reach the cloud through some topology, but no application
     *                     module is edge-placed on them — module placement is handled separately
     *                     in ScenarioBuilder, this flag only affects which values are cosmetic)
     */
    public void build(int userId, String appId) {
        cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        proxy = createFogDevice("proxy", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(PROXY_TO_CLOUD_LATENCY_MS);
        fogDevices.add(proxy);

        fog1 = addFogNode("fog-1", userId, appId, proxy.getId(), FOG_1_PAIRS);
        fog2 = addFogNode("fog-2", userId, appId, proxy.getId(), FOG_2_PAIRS);
        fog3 = addFogNode("fog-3", userId, appId, proxy.getId(), FOG_3_PAIRS);
    }

    private FogDevice addFogNode(String name, int userId, String appId, int parentId, int pairCount) {
        // Fog node sizing follows the reference literature's roadside-unit profile
        // (comparable to the "router" tier device in iFogSim2's own DCNS example):
        // modest MIPS/RAM, since these are edge boxes, not data-centre hardware.
        FogDevice node = createFogDevice(name, 4000, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
        node.setParentId(parentId);
        node.setUplinkLatency(FOG_TO_PROXY_LATENCY_MS);
        fogDevices.add(node);

        for (int i = 0; i < pairCount; i++) {
            String unitId = name + "-unit-" + i;
            Sensor sensor = new Sensor("s-" + unitId, "SENSOR_DATA", userId, appId,
                    new DeterministicDistribution(SENSOR_PERIOD_MS));
            sensor.setGatewayDeviceId(node.getId());
            sensor.setLatency(EDGE_LATENCY_MS);
            sensors.add(sensor);

            Actuator ecu = new Actuator("ecu-" + unitId, userId, appId, "ECU_ACTION");
            ecu.setGatewayDeviceId(node.getId());
            ecu.setLatency(EDGE_LATENCY_MS);
            actuators.add(ecu);
        }
        return node;
    }

    private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw,
            int level, double ratePerMips, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1_000_000;
        int bw = 10000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);

        FogDevice fogDevice = null;
        try {
            fogDevice = new FogDevice(nodeName, characteristics,
                    new AppModuleAllocationPolicy(hostList), new LinkedList<Storage>(),
                    10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create fog device " + nodeName, e);
        }
        fogDevice.setLevel(level);
        return fogDevice;
    }
}
