package com.example.demo;

import java.io.*;
import java.nio.charset.Charset;
import java.util.regex.*;

public class RouteManager {

    private final String adapterName;
    private final String adapterIp;
    private final String serverIp;

    private DefaultRoute oldDefault;

    public RouteManager(String adapterName, String adapterIp, String serverIp) {
        this.adapterName = adapterName;
        this.adapterIp = adapterIp;
        this.serverIp = serverIp;
    }

    public void start() throws Exception {
        oldDefault = findDefaultRoute();

        run("netsh interface ipv4 set address name=\"" + adapterName + "\" static " + adapterIp + " 255.255.255.255");
        run("netsh interface ipv4 set subinterface \"" + adapterName + "\" mtu=1200 store=active");

        // сервер VPN оставить через обычный интернет
        runIgnore("route delete " + serverIp);
        run("route add " + serverIp + " mask 255.255.255.255 " + oldDefault.gateway + " metric 1");

        // удалить обычный default
        runIgnore("route delete 0.0.0.0");

        // поставить default через Wintun по имени адаптера
        ps("New-NetRoute -DestinationPrefix '0.0.0.0/0' " +
                "-InterfaceAlias '" + adapterName + "' " +
                "-NextHop '0.0.0.0' " +
                "-RouteMetric 1 " +
                "-PolicyStore ActiveStore");

        run("route print -4");
    }

    public void stop() {
        // удалить VPN default
        psIgnore("Remove-NetRoute -DestinationPrefix '0.0.0.0/0' " +
                "-InterfaceAlias '" + adapterName + "' " +
                "-NextHop '0.0.0.0' " +
                "-Confirm:$false");

        runIgnore("route delete 0.0.0.0");

        // вернуть старый default
        if (oldDefault != null) {
            runIgnore("route add 0.0.0.0 mask 0.0.0.0 " +
                    oldDefault.gateway + " metric " + oldDefault.metric);
        }

        // удалить маршрут до сервера
        runIgnore("route delete " + serverIp);

        runIgnore("route print -4");
    }

    private DefaultRoute findDefaultRoute() throws Exception {
        String out = run("route print -4");

        Pattern p = Pattern.compile(
                "^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+" +
                        "(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+" +
                        "(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+" +
                        "(\\d+)\\s*$",
                Pattern.MULTILINE
        );

        Matcher m = p.matcher(out);
        if (!m.find()) {
            throw new RuntimeException("Не найден старый default route");
        }

        return new DefaultRoute(m.group(1), Integer.parseInt(m.group(3)));
    }

    private void ps(String command) throws Exception {
        run("powershell -NoProfile -ExecutionPolicy Bypass -Command \"" + command + "\"");
    }

    private void psIgnore(String command) {
        try {
            ps(command);
        } catch (Exception ignored) {
        }
    }

    private String run(String command) throws Exception {
        System.out.println("RUN: " + command);

        Process p = new ProcessBuilder("cmd.exe", "/c", command)
                .redirectErrorStream(true)
                .start();

        StringBuilder out = new StringBuilder();

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charset.forName("CP866"))
        )) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }

        int code = p.waitFor();
        String result = out.toString();

        if (!result.isBlank()) {
            System.out.println(result);
        }

        if (code != 0) {
            throw new RuntimeException("Command failed: " + command + "\n" + result);
        }

        return result;
    }

    private void runIgnore(String command) {
        try {
            run(command);
        } catch (Exception ignored) {
        }
    }

    private static class DefaultRoute {
        private final String gateway;
        private final int metric;

        private DefaultRoute(String gateway, int metric) {
            this.gateway = gateway;
            this.metric = metric;
        }
    }
}