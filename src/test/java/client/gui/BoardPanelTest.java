package client.gui;

import common.GameStateDTO;
import common.WallOrientation;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Testa entrada e pintura em memória, sem abrir janelas durante o build. */
class BoardPanelTest {
    @Test void mouseArrowsAndTurnGating() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> calls = new ArrayList<>();
            BoardPanel board = new BoardPanel(new BoardPanel.Actions() {
                public void requestMove(int row, int column) { calls.add("move " + row + " " + column); }
                public void requestWall(GameStateDTO.WallDTO wall) { calls.add("wall " + wall.row() + " " + wall.column()); }
            });
            board.setSize(650, 650); board.update(BoardHintsTest.initial(), 0, true);
            // Centro da casa (1,4), usando a geometria pública do painel de 650x650.
            board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_CLICKED, 0, 0, 325, 132, 1, false, MouseEvent.BUTTON1));
            assertEquals(List.of("move 1 4"), calls);
            board.getActionMap().get("DOWN").actionPerformed(null);
            assertEquals(2, calls.size());
            board.update(BoardHintsTest.initial(), 0, false);
            board.getActionMap().get("DOWN").actionPerformed(null);
            board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_CLICKED, 0, 0, 325, 132, 1, false, MouseEvent.BUTTON1));
            assertEquals(2, calls.size());
        });
    }
    @Test void wallPreviewClickAndRotationUseCorrectAnchors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<GameStateDTO.WallDTO> calls = new ArrayList<>();
            BoardPanel board = new BoardPanel(new BoardPanel.Actions() {
                public void requestMove(int row, int column) { fail("Não deve mover no modo parede."); }
                public void requestWall(GameStateDTO.WallDTO wall) { calls.add(wall); }
            });
            board.setSize(650, 650); board.update(BoardHintsTest.initial(), 0, true); board.wallMode(true);
            board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_CLICKED, 0, 0, 261, 293, 1, false, MouseEvent.BUTTON1));
            assertEquals(new GameStateDTO.WallDTO(3, 3, WallOrientation.HORIZONTAL), calls.get(0));
            board.orientation(WallOrientation.VERTICAL);
            board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_CLICKED, 0, 0, 293, 261, 1, false, MouseEvent.BUTTON1));
            assertEquals(new GameStateDTO.WallDTO(3, 3, WallOrientation.VERTICAL), calls.get(1));
            board.update(BoardHintsTest.withWalls(BoardHintsTest.initial(), calls.get(0)), 0, true);
            board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_CLICKED, 0, 0, 293, 261, 1, false, MouseEvent.BUTTON1));
            assertEquals(2, calls.size(), "A parede cruzada não pode ser enviada por clique.");
        });
    }
    @Test void renderBoardToImageForVisualInspection() throws Exception {
        BufferedImage image = new BufferedImage(650, 650, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            BoardPanel board = new BoardPanel(new BoardPanel.Actions() {
                public void requestMove(int row, int column) { }
                public void requestWall(GameStateDTO.WallDTO wall) { }
            });
            board.setSize(650, 650);
            board.update(BoardHintsTest.withWalls(BoardHintsTest.initial(),
                    new GameStateDTO.WallDTO(2, 2, WallOrientation.HORIZONTAL),
                    new GameStateDTO.WallDTO(5, 5, WallOrientation.VERTICAL)), 0, true);
            var graphics = image.createGraphics();
            try { board.paint(graphics); } finally { graphics.dispose(); }
        });
        assertNotEquals(image.getRGB(325, 68), image.getRGB(100, 100));
        ImageIO.write(image, "png", Path.of("target", "gui-board-preview.png").toFile());
    }
}
