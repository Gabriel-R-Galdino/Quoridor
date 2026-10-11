# Quoridor distribuído para 4 jogadores

Trabalho de Sistemas Distribuídos com interface de terminal. O motor de regras, os contratos compartilhados e o servidor RMI estão implementados e testados. O cliente de terminal ainda é um esqueleto.

## Tecnologias e requisitos

- Java 17 ou superior e Maven.
- Nenhuma dependência externa da aplicação: apenas o JDK. JUnit 5 é uma dependência exclusiva de testes; os plugins Maven são ferramentas de build.
- Cinco processos separados: um servidor e quatro clientes, um por jogador.
- Todos os processos na mesma máquina, usando `localhost`.
- Toda a comunicação entre processos via Java RMI, incluindo callbacks do servidor para os clientes.
- É proibido usar diretamente `java.net.Socket` ou `java.net.ServerSocket` no código da aplicação.

## Estrutura

```text
pom.xml
src/main/java/
├── model/
│   ├── Board.java
│   ├── Player.java
│   ├── Position.java
│   ├── Wall.java
│   ├── GameState.java
│   └── QuoridorRules.java
├── common/
│   ├── GameService.java
│   ├── ClientCallback.java
│   ├── GameException.java
│   ├── GameStateDTO.java
│   └── WallOrientation.java
├── server/
│   ├── ServerMain.java
│   └── GameServerImpl.java
└── client/
    ├── ClientMain.java
    ├── ClientCallbackImpl.java
    └── TerminalUI.java

src/test/java/model/QuoridorRulesTest.java
src/test/java/server/GameServerImplTest.java
```

| Pacote | Responsabilidade |
| --- | --- |
| `model` | Estado e regras puras, sem RMI ou interface. |
| `common` | Interfaces remotas e DTOs serializáveis compartilhados. |
| `server` | Registry, partida autoritativa, validação e notificações. |
| `client` | Conexão, exportação do callback e interface de caracteres. |

## Motor de regras implementado

O tabuleiro tem 9x9 casas. As coordenadas são `(linha, coluna)`, de 0 a 8; a linha 0 fica ao norte. Os IDs e a ordem fixa de turnos são: 0 norte, 1 sul, 2 leste, 3 oeste. Cada jogador começa no centro do seu lado com cinco paredes e vence ao atingir qualquer casa do lado oposto.

`QuoridorRules` mantém uma partida. Pedidos inválidos retornam `false` sem consumir turno, parede ou alterar o estado. Uma jogada válida consome o turno; após a vitória, novos movimentos e paredes são recusados.

| Método | Uso |
| --- | --- |
| `tryMove(playerId, row, column)` | Pede um movimento para uma casa absoluta. |
| `tryPlaceWall(playerId, row, column, orientation)` | Pede uma parede horizontal ou vertical. |
| `currentPlayer()` | Retorna o jogador da vez; `null` se todos estiverem desconectados. |
| `getWinner()` | Retorna o vencedor, ou `null` enquanto não houver vencedor. |
| `markDisconnected(playerId)` | Desconecta um jogador, preserva seu peão e pula seus turnos. |
| `getState()` | Permite consultar jogadores e paredes, sem mutação pela API pública. |
| `renderAscii()` | Produz o tabuleiro e a situação em texto; peões 1 a 4 correspondem aos IDs 0 a 3. |

As paredes usam uma interseção `(linha, coluna)` de 0 a 7. Uma parede `HORIZONTAL` em `(r,c)` bloqueia as passagens de `(r,c)` para `(r+1,c)` e de `(r,c+1)` para `(r+1,c+1)`. Uma `VERTICAL` bloqueia `(r,c)` para `(r,c+1)` e `(r+1,c)` para `(r+1,c+1)`. Pontas podem se tocar; sobreposição de segmentos e cruzamento na mesma interseção são proibidos.

O movimento normal é ortogonal. Um peão adjacente pode ser saltado se ambas as passagens estiverem livres. Se parede, borda ou outro peão impedir a chegada atrás dele, o motor permite contorná-lo diagonalmente pelas laterais livres, sem saltar uma cadeia de peões. Uma parede entre os dois peões impede o salto. Peões desconectados continuam ocupando suas casas.

Antes de aceitar uma parede, o motor executa uma BFS para cada um dos quatro jogadores, incluindo os desconectados. Essa busca verifica caminhos pelas passagens do tabuleiro e ignora peões; testa a parede candidata sem modificar o estado. A parede e seu custo só são aplicados se todos ainda puderem alcançar seu objetivo.

Referência das regras de base: [manual oficial da Gigamic](https://export.gigamic.com/wp-content/uploads/2021/01/INS-RULES-QUORIDOR_10-2016.pdf). O tratamento de outro peão atrás como impedimento para salto reto segue o requisito deste trabalho.

```java
QuoridorRules game = new QuoridorRules();
boolean accepted = game.tryMove(0, 1, 4);
System.out.println(game.renderAscii());
```

O exemplo pressupõe `import model.QuoridorRules;`. O motor não usa RMI e não é responsável por sincronizar chamadas concorrentes: o servidor controla esse acesso.

## Servidor RMI

`ServerMain` cria o Registry embutido com `LocateRegistry.createRegistry(1099)`, exporta o serviço remoto e o registra como `QuoridorService`. Não é necessário iniciar `rmiregistry` separadamente. A porta 1099 pertence ao Registry; os objetos remotos têm suas portas de exportação gerenciadas pelo RMI.

Cada futuro cliente será iniciado em sua própria JVM, consultará o Registry em `localhost:1099`, exportará um objeto que implementa `ClientCallback` e enviará seu stub ao servidor no registro do jogador. O servidor armazena essas referências para enviar atualizações por callback RMI. O quarto registro inicia automaticamente a partida.

| Operação de `GameService` | Comportamento |
| --- | --- |
| `join(nome, callback)` | Retorna ID de 0 a 3. Exige nome válido e callback; a quarta entrada inicia o jogo. |
| `move(playerId, linha, coluna)` | Valida um destino absoluto de 0 a 8, incluindo saltos. |
| `placeWall(playerId, linha, coluna, orientation)` | Valida uma parede em interseção de 0 a 7, com `WallOrientation`. |
| `leave(playerId)` | Libera a vaga antes do início; durante a partida, desconecta e preserva o peão. |

Todas as operações declaram `RemoteException` para falhas de comunicação e `GameException` para recusas do jogo. Uma recusa traz uma mensagem clara em português e não altera o turno nem os recursos. Depois do início, não há entrada de novos jogadores nem reconexão nesta versão. Após a vitória, jogadas são recusadas.

O servidor confere registro, início da partida, vencedor e turno antes de usar o motor de regras. Seus métodos remotos são `synchronized` e protegem todo o estado da partida. As notificações usam uma fila única e ocorrem fora do monitor, preservando a ordem dos eventos sem manter o estado bloqueado durante uma chamada remota.

`ClientCallback` define `onMessage(String)`, `onGameStarted()`, `onStateUpdate(GameStateDTO)` e `onGameOver(int vencedor)`. O vencedor é um ID de 0 a 3. O servidor envia a atualização final antes do aviso de vitória. Callbacks são assíncronos em relação ao retorno dos pedidos; o cliente deverá coordená-los com a leitura do teclado e aceitar que uma notificação possa chegar antes do retorno de `join`.

Se qualquer callback lançar `RemoteException`, o servidor registra o erro, desconecta aquele jogador e publica uma nova atualização aos demais. Durante a partida, o motor pula seus turnos e mantém o peão como obstáculo. Antes do início, a vaga fica disponível para outro cliente. Também são tratadas falhas de execução lançadas pelo callback. A detecção ocorre ao tentar notificar o cliente; não existe monitor periódico nesta versão.

`GameStateDTO` é serializável e imutável, com versão, tamanho do tabuleiro, indicação de início, turno, vencedor, jogadores e paredes. Cada jogador inclui ID, nome, lado, posição, paredes restantes e flags de entrada e conexão. Os DTOs não dependem de `model`; o estado interno nunca é enviado diretamente. O turno do DTO é `null` antes do início, após a vitória ou quando não há jogadores conectados.

O console registra entradas, início, movimentos, paredes, recusas, falhas de callback, desconexões, vitória e encerramento.

## Compilação

Na raiz, com Java 17+ e Maven instalados:

```text
mvn clean package
```

O resultado será `target/quoridor-distribuido-0.1.0-SNAPSHOT.jar`. Inicie o servidor com:

```text
java -cp target/quoridor-distribuido-0.1.0-SNAPSHOT.jar server.ServerMain
```

Ou, após `mvn compile`, use `java -cp target/classes server.ServerMain`. Encerre com Ctrl+C. A porta 1099 precisa estar livre. O JAR não possui manifesto para `java -jar`; o ponto de entrada é informado pelo comando acima. `ClientMain` ainda não é executável.

Para executar somente os testes:

```text
mvn test
```

Os testes cobrem as regras do modelo e o serviço: início automático, recusas, snapshots serializáveis, desconexões, falhas nos quatro métodos de callback, vitória, concorrência e callback lento sem bloqueio do estado. O teste de integração exporta um servidor e quatro callbacks, consulta um Registry remoto e simula a queda de um objeto exportado, usando exclusivamente RMI em localhost. Ele usa uma porta dinâmica para não ocupar a 1099 e roda na mesma JVM de testes; a demonstração em cinco processos será feita com os clientes de terminal.

## Próximas etapas

1. Implementar clientes, exportação dos callbacks e interface de terminal.
2. Demonstrar uma partida em cinco processos separados.
