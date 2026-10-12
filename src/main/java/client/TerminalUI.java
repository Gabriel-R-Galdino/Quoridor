package client;

import common.GameException;
import common.GameService;
import common.GameStateDTO;
import common.WallOrientation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Uma fila reúne teclado e callbacks. Somente o laço da interface escreve no console.
 * A leitura bloqueante ocorre em uma thread daemon e nunca bloqueia uma chamada RMI.
 */
public final class TerminalUI {
    private sealed interface Event { }
    private record StateEvent(GameStateDTO state) implements Event { }
    private record MessageEvent(String message) implements Event { }
    private record StartedEvent() implements Event { }
    private record OverEvent(int winner) implements Event { }
    private record InputEvent(String line) implements Event { }
    private record EndInputEvent() implements Event { }

    private final BufferedReader input;
    private final PrintWriter output;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private GameStateDTO state;
    private int playerId;
    private Long pendingVersion;
    private boolean gameOver;

    public TerminalUI(Reader input, PrintWriter output) {
        this.input = input instanceof BufferedReader reader ? reader : new BufferedReader(input);
        this.output = output;
    }

    /** Antes do registro não há leitura concorrente nem callbacks para este cliente. */
    public String readName() throws IOException {
        while (true) {
            output.print("Nome do jogador: ");
            output.flush();
            String name = input.readLine();
            if (name == null) { return null; }
            name = name.strip();
            if (!name.isEmpty() && name.length() <= 40 && name.codePoints().noneMatch(Character::isISOControl)) {
                return name;
            }
            output.println("Use um nome de 1 a 40 caracteres, sem caracteres de controle.");
        }
    }

    // Chamadas rápidas: uma notificação pode chegar antes de join retornar o ID.
    public void onStateUpdate(GameStateDTO state) { events.add(new StateEvent(state)); }
    public void onMessage(String message) { events.add(new MessageEvent(message)); }
    public void onGameStarted() { events.add(new StartedEvent()); }
    public void onGameOver(int winner) { events.add(new OverEvent(winner)); }

    /** Retorna ao digitar q, fechar a entrada ou interromper a thread da interface. */
    public void run(GameService service, int playerId) throws InterruptedException {
        this.playerId = playerId;
        output.println("Você é o jogador [" + symbol(playerId) + "], ID " + playerId + ".");
        output.println("Comandos: m w|a|s|d; p L C h|v (0 a 7); q para sair a qualquer momento.");
        output.println("Saltos diagonais: m wa|wd|sa|sd. O servidor valida todas as jogadas.");
        output.flush();
        Thread keyboard = new Thread(this::readCommands, "quoridor-teclado");
        keyboard.setDaemon(true);
        keyboard.start();
        try {
            boolean running = true;
            while (running) {
                Event event = events.take();
                if (event instanceof StateEvent update) {
                    if (state != null && update.state().version() <= state.version()) { continue; }
                    state = update.state();
                    gameOver = state.winnerId() != null;
                    if (pendingVersion != null && state.version() > pendingVersion) { pendingVersion = null; }
                    // Imprime um retrato completo, sem depender de suporte a cores ou ANSI.
                    output.println();
                    output.print(renderAscii(state, playerId));
                } else if (event instanceof MessageEvent message) {
                    output.println("Servidor: " + message.message());
                } else if (event instanceof StartedEvent) {
                    output.println("A partida começou!");
                } else if (event instanceof OverEvent over) {
                    gameOver = true;
                    output.println("Fim do jogo! Vencedor: " + playerLabel(state, over.winner()) + ". Digite q para sair.");
                } else if (event instanceof InputEvent command) {
                    running = handleCommand(service, command.line());
                } else if (event instanceof EndInputEvent) {
                    output.println("Entrada encerrada. Saindo do jogo.");
                    running = false;
                }
                if (running) { output.print("> "); }
                output.flush();
            }
        } finally {
            // Em console, readLine pode continuar bloqueado; sendo daemon, não impede a saída.
            keyboard.interrupt();
        }
    }

    private void readCommands() {
        try {
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = input.readLine()) != null) {
                events.add(new InputEvent(line));
            }
        } catch (IOException error) {
            events.add(new MessageEvent("Erro ao ler o teclado: " + error.getMessage()));
        } finally {
            events.add(new EndInputEvent());
        }
    }

    private boolean handleCommand(GameService service, String line) {
        try {
            TerminalCommand command = TerminalCommand.parse(line);
            if (command instanceof TerminalCommand.Quit) {
                output.println("Saindo do jogo.");
                return false;
            }
            if (gameOver) { output.println("A partida terminou. Digite q para sair."); return true; }
            if (state == null || !state.started()) {
                output.println("Aguardando os quatro jogadores e o estado inicial.");
                return true;
            }
            if (!Integer.valueOf(playerId).equals(state.currentPlayerId())) {
                output.println("Ainda não é sua vez. " + turnLabel(state));
                return true;
            }
            if (pendingVersion != null) {
                output.println("Aguardando a atualização do servidor sobre seu último pedido.");
                return true;
            }
            if (command instanceof TerminalCommand.Move move) {
                int[] destination = move.destination(state, playerId);
                service.move(playerId, destination[0], destination[1]);
            } else if (command instanceof TerminalCommand.PlaceWall wall) {
                service.placeWall(playerId, wall.row(), wall.column(), wall.orientation());
            }
            pendingVersion = state.version();
        } catch (IllegalArgumentException error) {
            output.println("Erro: " + error.getMessage());
        } catch (GameException error) {
            // Uma recusa não encerra a aplicação nem impede a correção do comando.
            output.println("Erro do servidor: " + error.getMessage());
        } catch (RemoteException error) {
            output.println("Falha de comunicação com o servidor: " + error.getMessage());
            output.println("Você pode tentar novamente ou digitar q para sair.");
        }
        return true;
    }

    /** Desenha somente os dados recebidos; não importa nem executa o modelo de regras. */
    public static String renderAscii(GameStateDTO state, int localPlayerId) {
        int size = state.boardSize();
        char[][] grid = new char[size * 2 - 1][size * 4 - 3];
        for (char[] row : grid) { Arrays.fill(row, ' '); }
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) { grid[row * 2][column * 4] = '.'; }
        }
        for (GameStateDTO.WallDTO wall : state.walls()) {
            if (wall.orientation() == WallOrientation.HORIZONTAL) {
                Arrays.fill(grid[wall.row() * 2 + 1], wall.column() * 4, wall.column() * 4 + 5, '-');
            } else {
                for (int row = wall.row() * 2; row <= wall.row() * 2 + 2; row++) {
                    grid[row][wall.column() * 4 + 2] = '|';
                }
            }
        }
        for (GameStateDTO.PlayerDTO player : state.players()) {
            if (player.joined()) { grid[player.row() * 2][player.column() * 4] = symbol(player.id()); }
        }
        StringBuilder text = new StringBuilder("    ");
        for (int column = 0; column < size; column++) { text.append(column).append(column < size - 1 ? "   " : "\n"); }
        for (int row = 0; row < grid.length; row++) {
            text.append(row % 2 == 0 ? (row / 2) + "   " : "    ");
            text.append(grid[row]).append('\n');
        }
        for (GameStateDTO.PlayerDTO player : state.players()) {
            text.append('[').append(symbol(player.id())).append("] ")
                    .append(player.joined() ? player.name() : "aguardando jogador")
                    .append(" (").append(sideLabel(player.side())).append("): ")
                    .append(player.wallsRemaining()).append(" paredes");
            if (player.id() == localPlayerId) { text.append(" [você]"); }
            if (player.joined() && !player.connected()) { text.append(" [desconectado]"); }
            text.append('\n');
        }
        if (state.winnerId() != null) {
            text.append("PARTIDA ENCERRADA. Vencedor: ").append(playerLabel(state, state.winnerId()))
                    .append(". Digite q para sair.\n");
        } else if (!state.started()) {
            text.append("Aguardando quatro jogadores para começar.\n");
        } else {
            text.append(turnLabel(state)).append('\n');
            if (Integer.valueOf(localPlayerId).equals(state.currentPlayerId())) {
                text.append("SUA VEZ! Use m w|a|s|d ou p L C h|v.\n");
            } else { text.append("Aguarde sua vez; q continua disponível.\n"); }
        }
        return text.toString();
    }

    private static String turnLabel(GameStateDTO state) {
        return state.currentPlayerId() == null ? "Sem jogador da vez."
                : "Turno: " + playerLabel(state, state.currentPlayerId()) + ".";
    }

    private static String playerLabel(GameStateDTO state, int id) {
        String name = state == null ? "" : state.players().stream().filter(p -> p.id() == id)
                .map(GameStateDTO.PlayerDTO::name).findFirst().orElse("");
        return "[" + symbol(id) + "]" + (name.isEmpty() ? "" : " " + name);
    }

    private static char symbol(int playerId) { return (char) ('A' + playerId); }

    private static String sideLabel(String side) {
        return switch (side) {
            case "NORTH" -> "norte";
            case "SOUTH" -> "sul";
            case "EAST" -> "leste";
            case "WEST" -> "oeste";
            default -> side;
        };
    }
}
