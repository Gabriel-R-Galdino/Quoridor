package client.gui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;

/** Ponto de entrada independente: o cliente de terminal continua disponível. */
public final class GuiClientMain {
    private GuiClientMain() { }
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("O cliente Swing precisa de um ambiente gráfico.");
            return;
        }
        // Componentes Swing são criados na Event Dispatch Thread (EDT).
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Usa o tema padrão do Swing, sem biblioteca externa. */ }
            new GameWindow().setVisible(true);
        });
    }
}
