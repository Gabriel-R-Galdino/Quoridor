# Quoridor distribuído para 4 jogadores

Trabalho de Sistemas Distribuídos com interface de terminal. O motor de regras, os contratos compartilhados, o servidor RMI e o cliente de terminal estão implementados e testados. A partida usa um servidor e quatro clientes em cinco processos Java separados.

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
APRESENTACAO.md
scripts/
├── compilar.bat / compilar.sh
├── iniciar-servidor.bat / iniciar-servidor.sh
└── iniciar-cliente.bat / iniciar-cliente.sh
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
    ├── TerminalCommand.java
    └── TerminalUI.java

src/test/java/model/QuoridorRulesTest.java
src/test/java/server/GameServerImplTest.java
src/test/java/client/TerminalUITest.java
src/test/java/client/ClientProcessIntegrationTest.java
```

| Pacote | Responsabilidade |
| --- | --- |
| `model` | Estado e regras puras, sem RMI ou interface. |
| `common` | Interfaces remotas e DTOs serializáveis compartilhados. |
| `server` | Registry, partida autoritativa, validação e notificações. |
| `client` | Conexão, exportação do callback e interface de caracteres. |

## Como rodar com os scripts

Instale um **JDK 17+** e o **Maven**, com `java`, `javac` e `mvn` no `PATH`. Na primeira compilação, o Maven precisa baixar seus plugins e o JUnit. A aplicação usa somente o JDK em execução.

Compile **antes** de iniciar a partida. O script executa `mvn package`, incluindo os testes e a geração do JAR. Deixe a porta 1099 livre, pois um teste inicia um servidor real. Os scripts localizam a raiz pelo próprio caminho e podem ser chamados de outras pastas; os exemplos abaixo partem da raiz do repositório.

### Windows — PowerShell ou Prompt de Comando

Compile uma vez:

```text
.\scripts\compilar.bat
```

No **terminal 1**, inicie o servidor:

```text
.\scripts\iniciar-servidor.bat
```

Nos **terminais 2, 3, 4 e 5**, execute uma vez em cada terminal:

```text
.\scripts\iniciar-cliente.bat
```

### Linux/macOS — shell

Compile uma vez:

```sh
sh scripts/compilar.sh
```

No **terminal 1**, inicie o servidor:

```sh
sh scripts/iniciar-servidor.sh
```

Nos **terminais 2, 3, 4 e 5**, execute uma vez em cada terminal:

```sh
sh scripts/iniciar-cliente.sh
```

Cada cliente pede o nome do jogador. A quarta entrada inicia a partida. Os scripts mantêm o processo no terminal atual, permitindo ver as mensagens e digitar comandos; não abrem terminais adicionais. Há cinco processos da aplicação: um servidor e quatro clientes.

O cliente usa `localhost` por padrão. Pode ser informado explicitamente com `.\scripts\iniciar-cliente.bat localhost` ou `sh scripts/iniciar-cliente.sh localhost`. Os scripts de início avisam se o JAR estiver ausente; depois de mudar o código, compile novamente para atualizar o JAR.

Use `q` para sair de um cliente e Ctrl+C para encerrar o servidor. Para uma nova partida, encerre os processos anteriores e inicie novamente o servidor e os quatro clientes. O servidor mantém uma única partida por execução.

`compilar.bat` e `compilar.sh` encaminham argumentos adicionais ao Maven; por exemplo, `.\scripts\compilar.bat -o` usa o cache já disponível, sem baixar artefatos. A execução dos testes em cinco processos exige que o ambiente permita conexões RMI em localhost.

## Diagrama da arquitetura em texto

```text
Mesma máquina (localhost)

JVM 1: Cliente A -- stub GameService --+
JVM 2: Cliente B -- stub GameService --+
JVM 3: Cliente C -- stub GameService --+-- chamadas RMI --> JVM 5: Servidor
JVM 4: Cliente D -- stub GameService --+                    |
                                                         +-- Registry :1099
                                                         |   QuoridorService
                                                         +-- GameServerImpl
                                                         |   estado sincronizado
                                                         +-- QuoridorRules / model
                                                         +-- fila de notificações

JVMs 1 a 4: ClientCallbackImpl <--- callbacks RMI -------- fila de notificações
                 |
                 +-- fila de eventos da TerminalUI --> desenho ASCII e mensagens
```

O Registry está dentro da JVM do servidor. Cada cliente consulta `QuoridorService` para obter seu stub; depois, as jogadas vão ao objeto remoto do serviço. Cada cliente também exporta seu próprio callback e passa o stub dele ao servidor em `join`. Os callbacks retornam diretamente aos objetos remotos dos clientes. As portas dos objetos exportados são escolhidas pelo RMI; a 1099 é a porta do catálogo Registry.

## Fluxo de uma jogada

```text
cliente -> stub GameService -> servidor -> validação -> estado oficial
                                                    -> fila de callbacks
                                                    -> stubs ClientCallback
                                                    -> clientes redesenham o DTO
```

1. `TerminalUI.handleCommand` interpreta a entrada com `TerminalCommand`. Por exemplo, `m s` do jogador A na posição inicial pede o destino `(1,4)`.
2. O cliente chama `GameService.move(0,1,4)` no stub. O RMI transporta a invocação até `GameServerImpl.move`.
3. Sob `synchronized`, `requireTurn` verifica registro, início da partida, vencedor e turno. `QuoridorRules.tryMove` verifica paredes, ocupação e regras de movimento.
4. Se houver recusa, o servidor lança `GameException`; o cliente apresenta a mensagem e permite corrigir o comando. Nada é alterado no estado oficial.
5. Se a jogada for aceita, o servidor altera o estado, avança o turno ou detecta vitória, incrementa a versão e captura um `GameStateDTO` imutável.
6. `publish` enfileira as notificações. A chamada remota retorna sem esperar a entrega de todos os callbacks.
7. Fora do monitor do estado, a fila chama `onMessage` e `onStateUpdate` nos stubs de callback dos jogadores conectados; após uma vitória, também chama `onGameOver`.
8. `ClientCallbackImpl` coloca eventos na fila da interface. `TerminalUI` descarta versões antigas e redesenha o tabuleiro. Uma falha de callback faz o servidor desconectar aquele jogador e publicar a nova situação.

Pedidos de parede seguem o mesmo fluxo, usando `placeWall` e `QuoridorRules.tryPlaceWall`, com verificação de sobreposição, cruzamento, inventário e BFS para os quatro objetivos.

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

Cada cliente é iniciado em sua própria JVM, consulta o Registry em `localhost:1099`, exporta um objeto que implementa `ClientCallback` e envia seu stub ao servidor no registro do jogador. O servidor armazena essas referências para enviar atualizações por callback RMI. O quarto registro inicia automaticamente a partida.

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

O resultado será `target/quoridor-distribuido-0.1.0-SNAPSHOT.jar`. No primeiro terminal, inicie o servidor:

```text
java -cp target/quoridor-distribuido-0.1.0-SNAPSHOT.jar server.ServerMain
```

Em outros **quatro terminais**, execute uma vez em cada um:

```text
java -cp target/quoridor-distribuido-0.1.0-SNAPSHOT.jar client.ClientMain
```

O cliente pede o nome do jogador e usa `localhost` por padrão. Para informar explicitamente o host do Registry:

```text
java -cp target/quoridor-distribuido-0.1.0-SNAPSHOT.jar client.ClientMain localhost
```

Ou, após `mvn compile`, use `java -cp target/classes server.ServerMain` e `java -cp target/classes client.ClientMain`. A porta 1099 precisa estar livre. O JAR não possui manifesto para `java -jar`; o ponto de entrada é informado pelo comando. Encerre o servidor com Ctrl+C.

O host é um argumento de consulta ao Registry. Neste trabalho todos os processos ficam na mesma máquina, e os stubs de servidor e callbacks anunciam `localhost`; execução entre máquinas diferentes não é parte desta configuração.

## Como jogar pelo terminal

Os símbolos são fixos: `A` norte (ID 0), `B` sul (ID 1), `C` leste (ID 2) e `D` oeste (ID 3). Não é necessário suporte a cores ou códigos ANSI. Cada atualização mostra o tabuleiro completo, nomes, quantidades de paredes, desconexões, jogador da vez e vencedor. `-` representa parede horizontal; `|` representa parede vertical. As coordenadas das casas e interseções seguem a convenção descrita no motor.

| Comando | Pedido |
| --- | --- |
| `m w` | Mover para o norte (linha diminui). |
| `m a` | Mover para o oeste (coluna diminui). |
| `m s` | Mover para o sul (linha aumenta). |
| `m d` | Mover para o leste (coluna aumenta). |
| `p L C h` | Colocar parede horizontal na interseção `(L,C)`, de 0 a 7. |
| `p L C v` | Colocar parede vertical na interseção `(L,C)`, de 0 a 7. |
| `m wa`, `m wd`, `m sa`, `m sd` | Escolher destino diagonal: noroeste, nordeste, sudoeste ou sudeste. |
| `q` | Sair a qualquer momento, inclusive no turno de outro jogador. |

Por exemplo, `p 3 0 h` pede uma parede horizontal em `(3,0)`. Se uma direção simples apontar para um peão adjacente, o cliente pede a casa atrás desse peão, permitindo o salto reto. Se o salto reto estiver impedido, use uma das direções diagonais para escolher a lateral. O cliente apenas converte essas intenções em destinos; o servidor valida paredes, ocupação, saltos e turnos.

Os pedidos de jogada são enviados quando o último estado recebido indica sua vez. Após um pedido aceito, o cliente aguarda a próxima atualização antes de permitir outro. Se o estado ficar desatualizado, a validação final continua sendo do servidor. Comandos malformados e recusas do servidor mostram uma mensagem e permitem tentar novamente. Falhas de comunicação também são apresentadas sem encerrar automaticamente o terminal.

O callback apenas coloca eventos em uma fila. Um único laço imprime mensagens e atualizações, enquanto uma thread daemon lê o teclado. Isso permite redesenhar o tabuleiro mesmo durante a espera por um comando, sem bloquear callbacks e sem executar regras localmente. Atualizações com versão antiga são descartadas.

Após a vitória, o vencedor aparece com símbolo e nome, e novos comandos de jogada são recusados. Digite `q` para encerrar. `q`, fechamento da entrada e Ctrl+C tentam avisar `leave` ao servidor e removem a exportação do callback.

Para executar somente os testes:

```text
mvn test
```

Os testes cobrem as regras do modelo, o serviço e o cliente: início automático, recusas, snapshots serializáveis, desconexões, falhas nos quatro métodos de callback, vitória, concorrência, comandos, desenho, versões antigas e erros sem queda do terminal.

O teste RMI do servidor usa um Registry em porta dinâmica e quatro callbacks exportados. O teste `ClientProcessIntegrationTest` inicia **cinco JVMs separadas**, usa a porta 1099 e controla a entrada dos quatro clientes reais: verifica início, movimentos, parede, erro devolvido pelo servidor e saída com desconexão. Deixe a porta 1099 livre ao executar `mvn test`. Todos os processos criados pelo teste são encerrados ao final.

Para a apresentação, use os cinco terminais acima e demonstre uma jogada recusada, um salto, uma parede e a saída de um jogador sem interromper os demais.

## Revisão do requisito de comunicação

A revisão dos arquivos Java de `src/main/java` e `src/test/java` não encontrou referências diretas a `java.net.Socket` ou `java.net.ServerSocket`, nem importações de `java.net`. Conexões, consulta ao Registry, exportações e callbacks usam a API `java.rmi`. O transporte interno do RMI é responsabilidade do JDK.

Para repetir a busca com ripgrep, opcionalmente instalado:

```sh
rg -n 'java\.net|\bSocket\b|\bServerSocket\b' src -g '*.java'
```

Sem correspondências, `rg` retorna código 1. As menções neste README e no roteiro descrevem o requisito, não são código de comunicação.

O roteiro com explicações de stub, Registry, callback, sincronização, desconexão e arquivos específicos está em [APRESENTACAO.md](APRESENTACAO.md).
