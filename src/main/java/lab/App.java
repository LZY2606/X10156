package lab;

import java.nio.file.Path;

public final class App {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5232;
        String dataDir = "lab-data";
        long seed = 42L;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = args[++i];
                case "--seed" -> seed = Long.parseLong(args[++i]);
                default -> throw new IllegalArgumentException("unknown arg " + args[i]);
            }
        }
        Lab lab = new Lab(Path.of(dataDir), seed);
        HttpApi api = new HttpApi(lab);
        api.start(host, port);
        System.out.println("Webhook 投递实验室 listening on http://" + host + ":" + api.port());
        Thread.currentThread().join();
    }
}
