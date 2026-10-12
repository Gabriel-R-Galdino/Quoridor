package client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Demonstra a arquitetura exigida: cinco JVMs distintas comunicando-se por RMI. */
@Timeout(45)
class ClientProcessIntegrationTest {
    private static final class Child implements AutoCloseable {
        private final Process process;
        private final PrintWriter input;
        private final StringBuilder output = new StringBuilder();

        Child(String main, String... arguments) throws Exception {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    // Padroniza também os streams do JDK em Windows, além dos arquivos.
                    "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                    "-cp", System.getProperty("java.class.path"), main));
            command.addAll(List.of(arguments));
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            input = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
            Thread reader = new Thread(() -> {
                try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        synchronized (output) { output.append(buffer, 0, count); output.notifyAll(); }
                    }
                } catch (Exception error) {
                    synchronized (output) { output.append(error); output.notifyAll(); }
                }
            }, "saida-" + main);
            reader.setDaemon(true);
            reader.start();
        }

        int mark() { synchronized (output) { return output.length(); } }
        void send(String line) { input.println(line); }

        void await(String text, int after) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            synchronized (output) {
                while (output.indexOf(text, after) < 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) { fail("Não recebeu '" + text + "'. Saída:\n" + output); }
                    TimeUnit.NANOSECONDS.timedWait(output, remaining);
                }
            }
        }

        void exited() throws InterruptedException {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "O cliente não encerrou depois de q.");
            assertEquals(0, process.exitValue());
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
            }
            input.close();
        }
    }

    @Test
    void fourTerminalClientsPlayAndLeaveThroughSeparateProcesses() throws Exception {
        List<Child> clients = new ArrayList<>();
        try (Child server = new Child("server.ServerMain")) {
            try {
                server.await("Servidor RMI disponível em localhost:1099", 0);
                String[] names = {"Ana", "Bruno", "Carla", "Davi"};
                for (int id = 0; id < 4; id++) {
                    // Exercita tanto o host padrão quanto o argumento explícito.
                    Child client = id == 0 ? new Child("client.ClientMain")
                            : new Child("client.ClientMain", "localhost");
                    clients.add(client);
                    client.await("Nome do jogador:", 0);
                    client.send(names[id]);
                    client.await("Você é o jogador [" + (char) ('A' + id) + "]", 0);
                }
                for (Child client : clients) {
                    client.await("A partida começou!", 0);
                    client.await("Turno: [A] Ana.", 0);
                }

                clients.get(0).send("m z");
                clients.get(0).await("Direção inválida", 0);
                clients.get(0).send("m s");
                clients.get(1).await("Turno: [B] Bruno.", 0);

                clients.get(1).send("p 3 0 h");
                clients.get(2).await("Turno: [C] Carla.", 0);
                clients.get(1).await("[B] Bruno (sul): 4 paredes [você]", 0);
                clients.get(2).send("m a");
                clients.get(3).await("Turno: [D] Davi.", 0);

                int firstMark = clients.get(0).mark();
                clients.get(3).send("q");
                clients.get(3).exited();
                clients.get(0).await("[D] Davi (oeste): 5 paredes [desconectado]", firstMark);
                clients.get(0).await("SUA VEZ!", firstMark);

                // Uma recusa real do servidor não derruba o cliente nem consome seu turno.
                clients.get(0).send("p 3 0 h");
                clients.get(0).await("Erro do servidor: Parede inválida", firstMark);
                clients.get(0).send("m s");
                server.await("Jogador 0 moveu para (2,4)", 0);

                for (int id = 0; id < 3; id++) { clients.get(id).send("q"); }
                for (int id = 0; id < 3; id++) { clients.get(id).exited(); }
                server.await("Jogador 0 desconectado: saída solicitada", 0);
            } finally {
                for (Child client : clients) { client.close(); }
            }
        }
    }
}
