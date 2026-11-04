package client.gui;

import common.ClientCallback;
import common.GameService;
import common.GameStateDTO;
import javax.swing.SwingUtilities;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reutiliza o protocolo existente. Todo acesso remoto deve ocorrer fora da EDT. */
final class GuiSession implements AutoCloseable {
    private final GameService service;
    private final GuiCallback callback;
    private final int playerId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;

    private GuiSession(GameService service, GuiCallback callback, int playerId) {
        this.service = service;
        this.callback = callback;
        this.playerId = playerId;
        // Se a JVM terminar sem fechar a janela, ainda avisamos o servidor sobre a saída.
        shutdownHook = new Thread(this::close, "quoridor-gui-saida");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    static GuiSession connect(String host, String name, GuiCallback.Listener listener) throws Exception {
        requireBackground();
        System.setProperty("java.rmi.server.hostname", "localhost");
        GameService service = (GameService) LocateRegistry.getRegistry(host, 1099).lookup(GameService.SERVICE_NAME);
        GuiCallback callback = new GuiCallback(listener);
        // O servidor recebe o stub exportado e o usa para devolver eventos por RMI.
        ClientCallback stub = (ClientCallback) UnicastRemoteObject.exportObject(callback, 0);
        try {
            return new GuiSession(service, callback, service.join(name, stub));
        } catch (Exception error) {
            UnicastRemoteObject.unexportObject(callback, true);
            throw error;
        }
    }

    int playerId() { return playerId; }
    void move(int row, int column) throws Exception { requireBackground(); service.move(playerId, row, column); }
    void placeWall(GameStateDTO.WallDTO wall) throws Exception {
        requireBackground();
        service.placeWall(playerId, wall.row(), wall.column(), wall.orientation());
    }

    static void requireBackground() {
        if (SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("RMI não pode executar na EDT."); }
    }

    @Override
    public void close() {
        requireBackground();
        // O botão, o fechamento da janela e o shutdown hook podem chamar close ao mesmo tempo.
        if (!closed.compareAndSet(false, true)) { return; }
        try { service.leave(playerId); }
        catch (Exception error) { System.err.println("Não foi possível avisar a saída: " + error.getMessage()); }
        finally {
            try { UnicastRemoteObject.unexportObject(callback, true); }
            catch (Exception ignored) { /* O callback já foi removido. */ }
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* A JVM está encerrando. */ }
        }
    }
}
