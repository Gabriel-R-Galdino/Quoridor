package client.gui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.awt.CardLayout;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import common.GameStateDTO;
import javax.swing.JLabel;
import javax.swing.AbstractButton;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Inspeção de layout sem exibir janela ou abrir diálogos durante os testes. */
class GameWindowTest {
    @Test void lobbyDisplaysCountAndDisablesGameControls() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            GameWindow window = new GameWindow();
            try {
                var initial = BoardHintsTest.initial();
                var players = new ArrayList<>(initial.players());
                players.set(3, new GameStateDTO.PlayerDTO(3, "", "WEST", 4, 0, 5, false, false));
                window.stateUpdated(new GameStateDTO(3, 9, false, null, null, players, List.of()));
                List<Component> components = descendants(window.getContentPane());
                assertTrue(components.stream().anyMatch(component -> component instanceof JLabel label
                        && label.getText().equals("Aguardando jogadores (3/4)")));
                long controls = components.stream().filter(component -> component instanceof AbstractButton button
                        && (button.getText().equals("Modo parede") || button.getText().startsWith("Orientação:")))
                        .peek(component -> assertFalse(component.isEnabled())).count();
                assertEquals(2, controls);
            } finally { window.dispose(); }
        });
    }

    private static List<Component> descendants(Container container) {
        List<Component> result = new ArrayList<>();
        for (Component component : container.getComponents()) {
            result.add(component);
            if (component instanceof Container child) { result.addAll(descendants(child)); }
        }
        return result;
    }

    @Test void loginAndGameLayoutCanBeRenderedWithoutShowingWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A janela completa requer ambiente gráfico; os testes do JPanel não.");
        AtomicReference<BufferedImage> login = new AtomicReference<>(), game = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GameWindow window = new GameWindow();
            try {
                window.pack(); window.setSize(1060, 780); window.validate();
                login.set(snapshot(window));
                window.stateUpdated(BoardHintsTest.initial());
                ((CardLayout) window.getContentPane().getLayout()).show(window.getContentPane(), "game");
                window.validate();
                game.set(snapshot(window));
                assertTrue(window.getContentPane().getWidth() >= 1000);
                assertFalse(window.isVisible());
            } finally { window.dispose(); }
        });
        ImageIO.write(login.get(), "png", Path.of("target", "gui-login-preview.png").toFile());
        ImageIO.write(game.get(), "png", Path.of("target", "gui-window-preview.png").toFile());
    }
    private static BufferedImage snapshot(GameWindow window) {
        var panel = window.getContentPane();
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { panel.printAll(graphics); } finally { graphics.dispose(); }
        return image;
    }
}
