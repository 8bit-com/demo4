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

        runCmd(
                "netsh interface ipv4 add route " +
                        "prefix=0.0.0.0/1 " +
                        "interface=\"" + adapterName + "\" " +
                        "nexthop=0.0.0.0 " +
                        "metric=1 " +
                        "store=active"
        );

        runCmd(
                "netsh interface ipv4 add route " +
                        "prefix=128.0.0.0/1 " +
                        "interface=\"" + adapterName + "\" " +
                        "nexthop=0.0.0.0 " +
                        "metric=1 " +
                        "store=active"
        );

        runCmd("route print -4");
    }

    public void stop() {
        cleanupRoutesOnly();
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