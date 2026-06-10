package com.example.demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RouteManager {

    private final String adapterName;
    private final String adapterIp;
    private final String serverIp;

    private String defaultGateway;
    private int interfaceIndex;

    public RouteManager(String adapterName, String adapterIp, String serverIp) {
        this.adapterName = adapterName;
        this.adapterIp = adapterIp;
        this.serverIp = serverIp;
    }

    public void start() throws Exception {
        defaultGateway = findDefaultGateway();
        interfaceIndex = findWintunInterfaceIndex();

        cleanupRoutesOnly();

        System.out.println("WINTUN ROUTE INTERFACE INDEX: " + interfaceIndex);

        runCmd("netsh interface ipv4 set address name=" + interfaceIndex + " static " + adapterIp + " 255.255.255.255");
        runCmdIgnoreError("route delete " + serverIp);
        runCmd("route add " + serverIp + " mask 255.255.255.255 " + defaultGateway + " metric 1");
        runCmd("netsh interface ipv4 set subinterface " + interfaceIndex + " mtu=1200 store=active");

        addDefaultVpnRoute("0.0.0.0", "128.0.0.0");
        addDefaultVpnRoute("128.0.0.0", "128.0.0.0");

        runCmd("route print -4");
    }

    public void stop() {
        cleanupRoutesOnly();
    }

    private void addDefaultVpnRoute(String network, String mask) throws Exception {
        runCmdIgnoreError("netsh interface ipv4 delete route prefix=" + network + "/1 interface=" + interfaceIndex);
        runCmdIgnoreError("netsh interface ipv4 delete route prefix=" + network + "/1 interface=\"" + adapterName + "\"");
        runCmdIgnoreError("route delete " + network);
        runCmd("route add " + network + " mask " + mask + " 0.0.0.0 metric 1 if " + interfaceIndex);
    }

    private void cleanupRoutesOnly() {
        if (interfaceIndex > 0) {
            runCmdIgnoreError("netsh interface ipv4 delete route prefix=0.0.0.0/1 interface=" + interfaceIndex);
            runCmdIgnoreError("netsh interface ipv4 delete route prefix=128.0.0.0/1 interface=" + interfaceIndex);
        }
        runCmdIgnoreError("netsh interface ipv4 delete route prefix=0.0.0.0/1 interface=\"" + adapterName + "\"");
        runCmdIgnoreError("netsh interface ipv4 delete route prefix=128.0.0.0/1 interface=\"" + adapterName + "\"");
        runCmdIgnoreError("route delete " + serverIp);
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

    private int findWintunInterfaceIndex() throws Exception {
        String output = runCmd("powershell -NoProfile -Command \"Get-NetAdapter -IncludeHidden | Where-Object { $_.InterfaceDescription -like '*Wintun*' -or $_.Name -eq '" + adapterName + "' } | Sort-Object ifIndex -Descending | Select-Object -First 1 -ExpandProperty ifIndex\"");
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            throw new RuntimeException("Не найден Wintun/MyVPN интерфейс");
        }
        return Integer.parseInt(trimmed.split("\\R")[trimmed.split("\\R").length - 1].trim());
    }

    private String runCmd(String command) throws Exception {
        System.out.println("RUN: " + command);

        Process process = new ProcessBuilder("cmd.exe", "/c", command)
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("CP866")))) {
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
            throw new RuntimeException("Command failed, code=" + code + "\ncmd=" + command + "\n" + result);
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
