package server;

import common.GameService;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/** Inicia o Registry embutido e publica o serviço remoto na mesma JVM. */
public final class ServerMain {
    private ServerMain() { }

    public static void main(String[] args) throws RemoteException {
        // Os stubs apontarão para esta máquina. Nenhum transporte é implementado à mão.
        System.setProperty("java.rmi.server.hostname", "localhost");
        Registry registry = LocateRegistry.createRegistry(1099);
        GameServerImpl service = new GameServerImpl();
        try {
            // Porta 0 deixa o RMI escolher a porta do objeto; 1099 pertence ao Registry.
            GameService stub = (GameService) UnicastRemoteObject.exportObject(service, 0);
            registry.rebind(GameService.SERVICE_NAME, stub);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                service.close();
                unexport(service);
                unexport(registry);
            }, "quoridor-encerramento"));
            System.out.println("Servidor RMI disponível em localhost:1099/" + GameService.SERVICE_NAME);
            System.out.println("Aguardando quatro jogadores. Use Ctrl+C para encerrar.");
            // A infraestrutura RMI mantém a JVM ativa após o retorno do main.
        } catch (RemoteException | RuntimeException error) {
            service.close();
            unexport(service);
            unexport(registry);
            System.err.println("Erro ao publicar o servidor RMI: " + error);
            throw error;
        }
    }

    private static void unexport(Remote object) {
        try {
            UnicastRemoteObject.unexportObject(object, true);
        } catch (RemoteException error) {
            System.err.println("Objeto RMI já indisponível ao encerrar: " + error.getMessage());
        }
    }
}
