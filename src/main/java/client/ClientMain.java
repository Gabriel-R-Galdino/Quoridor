package client;

import common.ClientCallback;
import common.GameException;
import common.GameService;

import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicBoolean;

/** Execute em quatro terminais separados. O único argumento opcional é o host do Registry. */
public final class ClientMain {
    private ClientMain() { }

    public static void main(String[] args) {
        // Console usa a codificação do terminal; entrada redirecionada usa UTF-8.
        Console console = System.console();
        Reader input = console == null ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader();
        PrintWriter output = console == null
                ? new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true) : console.writer();
        if (args.length > 1 || (args.length == 1 && args[0].isBlank())) {
            output.println("Uso: java -cp <classpath> client.ClientMain [host]; padrão: localhost.");
            output.flush();
            return;
        }
        String host = args.length == 0 ? "localhost" : args[0].strip();
        // Todos os processos deste trabalho estão na mesma máquina.
        System.setProperty("java.rmi.server.hostname", "localhost");
        TerminalUI terminal = new TerminalUI(input, output);
        ClientCallbackImpl callback = new ClientCallbackImpl(terminal);
        GameService service = null;
        Integer playerId = null;
        boolean exported = false;
        Thread shutdownHook = null;
        AtomicBoolean cleaned = new AtomicBoolean();
        try {
            String name = terminal.readName();
            if (name == null) { output.println("Entrada encerrada antes do registro."); return; }
            Registry registry = LocateRegistry.getRegistry(host, 1099);
            service = (GameService) registry.lookup(GameService.SERVICE_NAME);
            ClientCallback stub = (ClientCallback) UnicastRemoteObject.exportObject(callback, 0);
            exported = true;
            playerId = service.join(name, stub);
            GameService joinedService = service;
            int joinedId = playerId;
            // Também libera a sessão e o objeto remoto quando o usuário usa Ctrl+C.
            shutdownHook = new Thread(() -> cleanup(joinedService, joinedId, callback, output, cleaned),
                    "quoridor-saida-cliente");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            output.println("Conectado ao Registry em " + host + ":1099.");
            terminal.run(service, playerId);
        } catch (GameException error) {
            output.println("Entrada recusada pelo servidor: " + error.getMessage());
        } catch (NotBoundException error) {
            output.println("Serviço QuoridorService não encontrado. Inicie o servidor primeiro.");
        } catch (IOException error) {
            output.println("Erro de conexão ou entrada: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            output.println("Cliente interrompido.");
        } finally {
            if (exported) { cleanup(service, playerId, callback, output, cleaned); }
            if (shutdownHook != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException shuttingDown) { /* A JVM já está encerrando. */ }
            }
            output.flush();
        }
    }

    private static void cleanup(GameService service, Integer playerId, ClientCallbackImpl callback,
                                PrintWriter output, AtomicBoolean cleaned) {
        if (!cleaned.compareAndSet(false, true)) { return; }
        try {
            if (service != null && playerId != null) { service.leave(playerId); }
        } catch (RemoteException | GameException error) {
            output.println("Não foi possível avisar a saída ao servidor: " + error.getMessage());
        } finally {
            try { UnicastRemoteObject.unexportObject(callback, true); }
            catch (RemoteException alreadyRemoved) { /* O objeto remoto já foi removido. */ }
            output.flush();
        }
    }
}
