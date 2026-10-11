package server;

import common.ClientCallback;
import common.GameException;
import common.GameService;
import common.GameStateDTO;
import common.WallOrientation;
import model.Board;
import model.Player;
import model.QuoridorRules;
import model.Wall;

import java.rmi.RemoteException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor autoritativo. O monitor desta instância protege sessões, turno e modelo.
 * Callbacks usam uma fila única, preservando a ordem dos eventos sem segurar o monitor.
 */
public final class GameServerImpl implements GameService, AutoCloseable {
    private record Session(String name, ClientCallback callback) { }
    private record Recipient(int id, Session session) { }

    private final QuoridorRules rules = new QuoridorRules();
    private final Session[] sessions = new Session[4];
    // Nomes permanecem disponíveis no DTO mesmo depois da desconexão na partida.
    private final String[] names = {"", "", "", ""};
    private final ExecutorService notifications = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "quoridor-callbacks");
        thread.setDaemon(true);
        return thread;
    });
    private boolean started;
    private boolean closed;
    private long version;

    @Override
    public synchronized int join(String nome, ClientCallback callback) throws GameException {
        requireOpen();
        if (started) { throw reject("A partida já começou; novas entradas não são permitidas."); }
        if (nome == null || nome.isBlank()) { throw reject("Informe um nome não vazio."); }
        if (callback == null) { throw reject("O callback do cliente é obrigatório."); }
        for (Session session : sessions) {
            if (session != null && session.callback().equals(callback)) {
                throw reject("Este callback já está registrado como jogador.");
            }
        }
        int id = 0;
        while (id < 4 && sessions[id] != null) { id++; }
        if (id == 4) { throw reject("A sala já possui quatro jogadores."); }
        String name = nome.strip();
        // Impede que quebras de linha do nome confundam os logs do terminal.
        if (name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw reject("O nome deve ter até 40 caracteres, sem caracteres de controle.");
        }
        sessions[id] = new Session(name, callback);
        names[id] = name;
        boolean justStarted = connectedCount() == 4;
        if (justStarted) {
            started = true;
            log("INÍCIO", "Quatro jogadores registrados. A partida começou.");
        }
        version++;
        String message = "Jogador " + id + " (" + name + ") entrou. " + connectedCount() + "/4 conectados.";
        log("ENTRADA", message);
        publish(message, justStarted, null);
        return id;
    }

    @Override
    public synchronized void move(int playerId, int linha, int coluna) throws GameException {
        requireTurn(playerId);
        if (!Board.contains(linha, coluna)) { throw reject("Destino fora do tabuleiro: use índices de 0 a 8."); }
        if (!rules.tryMove(playerId, linha, coluna)) {
            throw reject("Movimento inválido: casa ocupada, parede ou movimento/salto não permitido.");
        }
        version++;
        String message = "Jogador " + playerId + " moveu para (" + linha + "," + coluna + ").";
        log("JOGADA", message);
        Integer winner = rules.getWinner() == null ? null : rules.getWinner().id();
        if (winner != null) { log("VITÓRIA", "Vencedor: jogador " + winner + " (" + names[winner] + ")."); }
        publish(message, false, winner);
    }

    @Override
    public synchronized void placeWall(int playerId, int linha, int coluna, WallOrientation orientation)
            throws GameException {
        requireTurn(playerId);
        if (linha < 0 || linha > 7 || coluna < 0 || coluna > 7 || orientation == null) {
            throw reject("Parede inválida: use interseções de 0 a 7 e orientação HORIZONTAL ou VERTICAL.");
        }
        if (rules.currentPlayer().wallsRemaining() == 0) { throw reject("Você não possui paredes restantes."); }
        Wall.Orientation modelOrientation = Wall.Orientation.valueOf(orientation.name());
        if (!rules.tryPlaceWall(playerId, linha, coluna, modelOrientation)) {
            throw reject("Parede inválida: sobreposição, cruzamento ou bloqueio do último caminho de um jogador.");
        }
        version++;
        String message = "Jogador " + playerId + " colocou parede " + orientation
                + " em (" + linha + "," + coluna + ").";
        log("JOGADA", message);
        publish(message, false, null);
    }

    @Override
    public synchronized void leave(int playerId) throws GameException {
        requireOpen();
        requireSession(playerId);
        disconnect(playerId, "saída solicitada");
    }

    private void requireOpen() throws GameException {
        if (closed) { throw reject("O servidor está encerrando."); }
    }

    private void requireSession(int playerId) throws GameException {
        if (playerId < 0 || playerId >= 4 || sessions[playerId] == null) {
            throw reject("Jogador não registrado ou desconectado: " + playerId + ".");
        }
    }

    private void requireTurn(int playerId) throws GameException {
        requireOpen();
        requireSession(playerId);
        if (!started) { throw reject("Aguardando quatro jogadores para iniciar a partida."); }
        if (rules.getWinner() != null) { throw reject("A partida já terminou. Vencedor: " + rules.getWinner().id() + "."); }
        if (rules.currentPlayer() == null || rules.currentPlayer().id() != playerId) {
            throw reject("Não é seu turno. Jogador da vez: "
                    + (rules.currentPlayer() == null ? "nenhum" : rules.currentPlayer().id()) + ".");
        }
    }

    private GameException reject(String message) {
        log("ERRO", message);
        return new GameException(message);
    }

    private int connectedCount() {
        int count = 0;
        for (Session session : sessions) { if (session != null) { count++; } }
        return count;
    }

    // Chamado exclusivamente dentro do monitor do servidor.
    private void disconnect(int id, String reason) {
        sessions[id] = null;
        if (started) {
            rules.markDisconnected(id); // Mantém o peão como obstáculo e pula seus turnos.
        } else {
            names[id] = ""; // Antes do início, a vaga pode receber outro cliente.
        }
        version++;
        String message = "Jogador " + id + " desconectado: " + reason + ".";
        log("SAÍDA", message);
        publish(message, false, null);
    }

    private GameStateDTO snapshot() {
        List<GameStateDTO.PlayerDTO> players = rules.getState().getPlayers().stream().map(player ->
                new GameStateDTO.PlayerDTO(player.id(), names[player.id()], player.side().name(),
                        player.position().row(), player.position().column(), player.wallsRemaining(),
                        !names[player.id()].isEmpty(), sessions[player.id()] != null)).toList();
        List<GameStateDTO.WallDTO> walls = rules.getState().getBoard().getWalls().stream().map(wall ->
                new GameStateDTO.WallDTO(wall.row(), wall.column(),
                        WallOrientation.valueOf(wall.orientation().name()))).toList();
        Player winner = rules.getWinner();
        Player current = rules.currentPlayer();
        return new GameStateDTO(version, Board.SIZE, started,
                !started || winner != null || current == null ? null : current.id(),
                winner == null ? null : winner.id(), players, walls);
    }

    /** Captura dados e destinatários sob o monitor; a fila executará as chamadas depois. */
    private void publish(String message, boolean gameStarted, Integer winner) {
        GameStateDTO dto = snapshot();
        List<Recipient> recipients = new ArrayList<>();
        for (int id = 0; id < 4; id++) {
            if (sessions[id] != null) { recipients.add(new Recipient(id, sessions[id])); }
        }
        notifications.execute(() -> {
            for (Recipient recipient : recipients) {
                if (!isActive(recipient)) { continue; }
                try {
                    ClientCallback callback = recipient.session().callback();
                    callback.onMessage(message);
                    if (gameStarted) { callback.onGameStarted(); }
                    callback.onStateUpdate(dto);
                    if (winner != null) { callback.onGameOver(winner); }
                } catch (RemoteException | RuntimeException error) {
                    callbackFailed(recipient, error);
                }
            }
        });
    }

    private synchronized boolean isActive(Recipient recipient) {
        return !closed && sessions[recipient.id()] == recipient.session();
    }

    /** Uma falha de uma sessão antiga não pode desconectar quem reutilizou sua vaga. */
    private synchronized void callbackFailed(Recipient recipient, Exception error) {
        if (!isActive(recipient)) { return; }
        log("ERRO", "Callback do jogador " + recipient.id() + " falhou: " + error);
        disconnect(recipient.id(), "falha no callback RMI");
    }

    private static void log(String event, String message) {
        System.out.printf("[%s] [%s] %s%n", LocalTime.now().withNano(0), event, message);
    }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        closed = true;
        notifications.shutdownNow();
        log("ENCERRAMENTO", "Servidor encerrado.");
    }
}
