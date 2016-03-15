package org.apache.mesos.elasticsearch.systemtest.util;

import com.containersol.minimesos.cluster.MesosCluster;
import com.containersol.minimesos.mesos.MesosAgent;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.jayway.awaitility.Awaitility;
import org.apache.commons.io.IOUtils;
import org.apache.mesos.elasticsearch.systemtest.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Adds ip table rules to forward all ES traffic to the docker host.
 */
public class IpTables implements Callable<Boolean> {
    private static final Logger LOGGER = LoggerFactory.getLogger(IpTables.class);
    public static final String IPTABLES_FINISHED_FLAG = "iptables_finished_flag";
    private final DockerClient client;
    private final Configuration config;
    private final String containerId;

    public static void apply(DockerClient client, MesosCluster cluster, Configuration config) {
        // Install IP tables and reroute traffic from slaves to ports exposed on host.
        for (MesosAgent slave : cluster.getAgents()) {
            LOGGER.debug("Applying iptable redirect to " + slave.getIpAddress());
            Awaitility.await().pollInterval(1L, TimeUnit.SECONDS).atMost(30, TimeUnit.SECONDS).until(new IpTables(client, config, slave.getContainerId()));
        }
    }

    private IpTables(DockerClient client, Configuration config, String containerId) {
        this.client = client;
        this.config = config;
        this.containerId = containerId;
    }

    @Override
    public Boolean call() throws Exception {
        String iptablesRoute = config.getPorts().stream().map(ports -> "" +
                        "sudo iptables -t nat -A PREROUTING -p tcp --dport " + ports.client() + " -j DNAT --to-destination " + Configuration.getDocker0AdaptorIpAddress() + ":" + ports.client() + " && " +
                        "sudo iptables -t nat -A PREROUTING -p tcp --dport " + ports.transport() + " -j DNAT --to-destination " + Configuration.getDocker0AdaptorIpAddress() + ":" + ports.transport() + " && "
        ).collect(Collectors.joining(" "));
        ExecCreateCmdResponse execResponse = client.execCreateCmd(containerId)
                .withAttachStdout()
                .withAttachStderr()
                .withTty(true)
                .withCmd("sh", "-c", "" +
                                "echo 1 > /proc/sys/net/ipv4/ip_forward && " +
                                iptablesRoute +
                                "sudo iptables -t nat -A POSTROUTING -j MASQUERADE  && " +
                                "echo " + IPTABLES_FINISHED_FLAG
                ).exec();
        try (InputStream inputStream = client.execStartCmd(containerId).withTty().withExecId(execResponse.getId()).exec()) {
            String log = IOUtils.toString(inputStream, "UTF-8");
            LOGGER.info("Install iptables log: " + log);
            return log.contains(IPTABLES_FINISHED_FLAG);
        } catch (IOException e) {
            LOGGER.error("Could not read log. Retrying.");
            return false;
        }
    }
}
