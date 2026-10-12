package client;

import common.ClientCallback;
import common.GameException;
import common.GameService;
import common.GameStateDTO;
import common.WallOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Testa o terminal por entrada e saída em memória, sem depender de cores ou teclado real. */
@Timeout(5)
class TerminalUITest {
    static GameStateDTO state(long version, boolean started, Integer turn, Integer winner) {
        return new GameStateDTO(version, 9, started, turn, winner, List.of(
                new GameStateDTO.PlayerDTO(0, "Ana", "NORTH", 0, 4, 5, true, true),
                new GameStateDTO.PlayerDTO(1, "Bruno", "SOUTH", 8, 4, 5, true, true),
                new GameStateDTO.PlayerDTO(2, "Carla", "EAST", 4, 8, 5, true, true),
                new GameStateDTO.PlayerDTO(3, "Davi", "WEST", 4, 0, 5, true, false)),
                List.of(new GameStateDTO.WallDTO(2, 2, WallOrientation.HORIZONTAL),
                        new GameStateDTO.WallDTO(5, 5, WallOrientation.VERTICAL)));
    }

    private static class FakeService implements GameService {
        final List<String> calls = new ArrayList<>();
        boolean rejectNext;
        boolean communicationFailure;

        @Override public int join(String name, ClientCallback callback) { return 0; }
        @Override public void leave(int id) { }

        @Override
        public void move(int id, int row, int column) throws GameException, RemoteException {
            calls.add("move " + id + " " + row + " " + column);
            if (communicationFailure) { throw new RemoteException("Servidor indisponível"); }
            if (rejectNext) { rejectNext = false; throw new GameException("Movimento bloqueado por parede."); }
        }

        @Override
        public void placeWall(int id, int row, int column, WallOrientation orientation) throws GameException {
            calls.add("wall " + id + " " + row + " " + column + " " + orientation);
            if (rejectNext) { rejectNext = false; throw new GameException("Parede sobreposta."); }
        }
    }

    @ParameterizedTest
    @CsvSource({"w,-1,0", "a,0,-1", "s,1,0", "d,0,1", "wa,-1,-1", "wd,-1,1", "sa,1,-1", "sd,1,1"})
    void parsesEveryMovementDirection(String direction, int rowDelta, int columnDelta) {
        assertEquals(new TerminalCommand.Move(rowDelta, columnDelta), TerminalCommand.parse("m " + direction));
    }

    @Test
    void parsesWallAndQuitIgnoringCaseAndExtraSpaces() {
        assertEquals(new TerminalCommand.PlaceWall(2, 7, WallOrientation.VERTICAL), TerminalCommand.parse("  P  2 7 V  "));
        assertEquals(new TerminalCommand.Quit(), TerminalCommand.parse(" Q "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "m", "m z", "m w extra", "p 2 x h", "p 2 3 z", "p 2 3", "q extra"})
    void rejectsMalformedCommandsWithoutContactingServer(String command) {
        assertThrows(IllegalArgumentException.class, () -> TerminalCommand.parse(command));
    }

    @Test
    void cardinalMovementRequestsJumpDestinationEvenOverDisconnectedPawn() {
        GameStateDTO original = state(4, true, 0, null);
        List<GameStateDTO.PlayerDTO> players = new ArrayList<>(original.players());
        players.set(0, new GameStateDTO.PlayerDTO(0, "Ana", "NORTH", 4, 4, 5, true, true));
        players.set(1, new GameStateDTO.PlayerDTO(1, "Bruno", "SOUTH", 5, 4, 5, true, false));
        GameStateDTO adjacent = new GameStateDTO(4, 9, true, 0, null, players, List.of());
        assertArrayEquals(new int[]{6, 4}, ((TerminalCommand.Move) TerminalCommand.parse("m s")).destination(adjacent, 0));
        assertArrayEquals(new int[]{5, 5}, ((TerminalCommand.Move) TerminalCommand.parse("m sd")).destination(adjacent, 0));
        assertArrayEquals(new int[]{4, 3}, ((TerminalCommand.Move) TerminalCommand.parse("m a")).destination(adjacent, 0));
    }

    @Test
    void asciiDisplaysNineRowsPawnsBothWallOrientationsInventoryAndTurn() {
        String ascii = TerminalUI.renderAscii(state(4, true, 0, null), 0);
        for (int row = 0; row < 9; row++) { assertTrue(ascii.contains("\n" + row + "   ")); }
        assertTrue(ascii.contains("-----"));
        assertTrue(ascii.contains("|"));
        assertTrue(ascii.contains("[A] Ana (norte): 5 paredes [você]"));
        assertTrue(ascii.contains("[D] Davi (oeste): 5 paredes [desconectado]"));
        assertTrue(ascii.contains("Turno: [A] Ana."));
        assertTrue(ascii.contains("SUA VEZ!"));
        assertTrue(ascii.contains("0   .   .   .   .   A"));
        assertTrue(ascii.contains("4   D"));
    }

    @Test
    void namePromptRetriesEmptyNameAndSupportsAccents() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("  \n  João  \n"), new PrintWriter(text));
        assertEquals("João", ui.readName());
        assertTrue(text.toString().contains("Use um nome"));
    }

    @Test
    void serverErrorAndBadCommandsKeepClientRunningAndPreventDuplicatePendingMove() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("m z\np 2 x h\nm s\nm s\nm s\nq\n"), new PrintWriter(text));
        FakeService service = new FakeService();
        service.rejectNext = true;
        ui.onStateUpdate(state(4, true, 0, null));
        ui.run(service, 0);
        assertEquals(List.of("move 0 1 4", "move 0 1 4"), service.calls);
        assertTrue(text.toString().contains("Erro do servidor: Movimento bloqueado por parede."));
        assertTrue(text.toString().contains("Direção inválida"));
        assertTrue(text.toString().contains("números inteiros"));
        assertTrue(text.toString().contains("Aguardando a atualização"));
        assertTrue(text.toString().contains("Saindo do jogo."));
    }

    @Test
    void wallCommandForwardsOrientationAndShowsServerRejection() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("p 3 4 v\np 2 3 h\nq\n"), new PrintWriter(text));
        FakeService service = new FakeService();
        service.rejectNext = true;
        ui.onStateUpdate(state(4, true, 0, null));
        ui.run(service, 0);
        assertEquals(List.of("wall 0 3 4 VERTICAL", "wall 0 2 3 HORIZONTAL"), service.calls);
        assertTrue(text.toString().contains("Erro do servidor: Parede sobreposta."));
    }

    @Test
    void waitingPlayerCanQuitButCannotSendMoves() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("m s\np 2 3 h\nq\n"), new PrintWriter(text));
        FakeService service = new FakeService();
        ui.onStateUpdate(state(4, true, 1, null));
        ui.run(service, 0);
        assertTrue(service.calls.isEmpty());
        assertTrue(text.toString().contains("Ainda não é sua vez. Turno: [B] Bruno."));
        assertTrue(text.toString().contains("Saindo do jogo."));
    }

    @Test
    void lobbyBlocksMovesAndEndOfInputExitsCleanly() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("m s\n"), new PrintWriter(text));
        FakeService service = new FakeService();
        ui.onStateUpdate(state(1, false, null, null));
        ui.run(service, 0);
        assertTrue(service.calls.isEmpty());
        assertTrue(text.toString().contains("Aguardando os quatro jogadores"));
        assertTrue(text.toString().contains("Entrada encerrada"));
    }

    @Test
    void callbackQueuesEarlyStateAndIgnoresOlderVersions() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("q\n"), new PrintWriter(text));
        ClientCallbackImpl callback = new ClientCallbackImpl(ui);
        callback.onGameStarted();
        callback.onStateUpdate(state(6, true, 2, null));
        callback.onStateUpdate(state(4, true, 0, null));
        ui.run(new FakeService(), 0);
        assertTrue(text.toString().contains("A partida começou!"));
        assertTrue(text.toString().contains("Turno: [C] Carla."));
        assertFalse(text.toString().contains("Turno: [A] Ana."));
    }

    @Test
    void gameOverShowsWinnerAndRejectsCommandsLocally() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("m s\nq\n"), new PrintWriter(text));
        FakeService service = new FakeService();
        ClientCallbackImpl callback = new ClientCallbackImpl(ui);
        callback.onStateUpdate(state(20, true, null, 2));
        callback.onGameOver(2);
        ui.run(service, 0);
        assertTrue(service.calls.isEmpty());
        assertTrue(text.toString().contains("PARTIDA ENCERRADA. Vencedor: [C] Carla"));
        assertTrue(text.toString().contains("Fim do jogo! Vencedor: [C] Carla"));
    }

    @Test
    void communicationFailureDoesNotCrashClient() throws Exception {
        StringWriter text = new StringWriter();
        TerminalUI ui = new TerminalUI(new StringReader("m s\nq\n"), new PrintWriter(text));
        FakeService service = new FakeService();
        service.communicationFailure = true;
        ui.onStateUpdate(state(4, true, 0, null));
        ui.run(service, 0);
        assertTrue(text.toString().contains("Falha de comunicação"));
        assertTrue(text.toString().contains("Saindo do jogo."));
    }
}
