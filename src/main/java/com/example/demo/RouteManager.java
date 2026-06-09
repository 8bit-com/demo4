package com.example.demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RouteManager {

    private static final List<String> VPN_DOMAINS = List.of(
            "chatgpt.com",
            "chat.openai.com",
            "auth.openai.com",
            "ab.chatgpt.com",
            "cdn.oaistatic.com",
            "oaistatic.com",
            "oaiusercontent.com"
    );

    private final String adapterName;
    private final String adapterIp;
    private final String serverIp;

    private String defaultGateway;
    private final Set<String> routedIps = new LinkedHashSet<>();

    public RouteManager(String adapterName, String adapterIp, String serverIp) {
        this.adapterName = adapterName;
        this.adapterIp = adapterIp;
        this.serverIp = serverIp;
    }

    public void start() throws Exception {
        cleanupRoutesOnly();

        defaultGateway = findDefaultGateway();

        runCmd(
                "netsh interface ipv4 set address " +
                        "name=\"" + adapterName + "\" " +
                        "static " +
                        adapterIp + " " +
                        "255.255.255.255"
        );

        runCmdIgnoreError("route delete " + serverIp);

        runCmd(
                "route add " + serverIp +
                        " mask 255.255.255.255 " +
                        defaultGateway +
                        " metric 1"
        );

        List<String> ips = resolveVpnIps();
        if (ips.isEmpty()) {
            throw new RuntimeException("Не удалось получить IPv4 для ChatGPT/OpenAI доменов");
        }

        for (String ip : ips) {
            addVpnRoute(ip);
        }

        runCmd("route print -4");
    }

    public void stop() {
        cleanupRoutesOnly();
    }

    private List<String> resolveVpnIps() throws Exception {
        Set<String> result = new LinkedHashSet<>();

        for (String domain : VPN_DOMAINS) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        result.add(ip);
                        System.out.println("VPN ROUTE DOMAIN " + domain + " -> " + ip);
                    }
                }
            } catch (Exception e) {
                System.out.println("DNS SKIP " + domain + ": " + e.getMessage());
            }
        }

        return new ArrayList<>(result);
    }

    private void addVpnRoute(String ip) throws Exception {
        runCmdIgnoreError(
                "netsh interface ipv4 delete route " +
                        "prefix=" + ip + "/32 " +
                        "interface=\"" + adapterName + "\""
        );

        runCmd(
                "netsh interface ipv4 add route " +
                        "prefix=" + ip + "/32 " +
                        "interface=\"" + adapterName + "\" " +
                        "nexthop=0.0.0.0 " +
                        "metric=1 " +
                        "store=active"
        );

        routedIps.add(ip);
    }

    private void cleanupRoutesOnly() {
        runCmdIgnoreError(
                "netsh interface ipv4 delete route " +
                        "prefix=0.0.0.0/1 " +
                        "interface=\"" + adapterName + "\""
        );

        runCmdIgnoreError(
                "netsh interface ipv4 delete route " +
                        "prefix=128.0.0.0/1 " +
                        "interface=\"" + adapterName + "\""
        );

        for (String ip : routedIps) {
            runCmdIgnoreError(
                    "netsh interface ipv4 delete route " +
                            "prefix=" + ip + "/32 " +
                            "interface=\"" + adapterName + "\""
            );
        }
        routedIps.clear();

        for (String domainIp : resolveVpnIpsQuietly()) {
            runCmdIgnoreError(
                    "netsh interface ipv4 delete route " +
                            "prefix=" + domainIp + "/32 " +
                            "interface=\"" + adapterName + "\""
            );
        }

        runCmdIgnoreError("route delete " + serverIp);
    }

    private Set<String> resolveVpnIpsQuietly() {
        Set<String> result = new LinkedHashSet<>();

        for (String domain : VPN_DOMAINS) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address) {
                        result.add(address.getHostAddress());
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return result;
    }

    private String findDefaultGateway() throws Exception {
        String output = runCmd("route print -4");

        Pattern pattern = Pattern.compile(
                "^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+\\d+\\s*$",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(output);

        if (!matcher.find()) {
            throw new RuntimeException("Не найден обычный default gateway");
        }

        return matcher.group(1);
    }

    private String runCmd(String command) throws Exception {
        System.out.println("RUN: " + command);

        Process process = new ProcessBuilder("cmd.exe", "/c", command)
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        Charset.forName("CP866")
                )
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        int code = process.waitFor();
        String result = output.toString();

        if (!result.isBlank()) {
            System.out.println(result);
        }

        if (code != 0) {
            throw new RuntimeException(
                    "Command failed, code=" + code +
                            "\ncmd=" + command +
                            "\n" + result
            );
        }

        return result;
    }

    private void runCmdIgnoreError(String command) {
        try {
            runCmd(command);
        } catch (Exception ignored) {
        }
    }
}
