# Desenvolvimento

Use o checkout existente em `/workspace/Shirokai-Commands`. Cada tarefa na nuvem já tem isolamento; não crie worktrees sem solicitação explícita.

JDK 21 e wrapper Gradle 8.10.2 são necessários. Na instância usada na implementação, o JDK fica em `/workspace/tools/jdk-21.0.13+9`; `GRADLE_USER_HOME=/workspace/.gradle` contém dependências e configuração local do proxy. Esses caminhos não são requisitos de uma máquina normal.

```sh
export JAVA_HOME=/workspace/tools/jdk-21.0.13+9
export GRADLE_USER_HOME=/workspace/.gradle
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
./gradlew build --console=plain
python3 scripts/integration-test.py
```

A trust store acima usa as autoridades confiáveis fornecidas pela imagem da nuvem. Fora dessa imagem, use a trust store normal do JDK e o proxy da sua infraestrutura; não desative TLS. A configuração local do Gradle traduz o proxy herdado para `systemProp.https.proxyHost/Port` e `http.proxyHost/Port`. Não versione credenciais ou parâmetros específicos do proxy. Rede requerida: repositórios Maven/Fabric, Mojang, distribuição Gradle e GitHub; para obter LuckPerms via Modrinth, `api.modrinth.com` e `cdn.modrinth.com`.

Para iniciar desenvolvimento manual:

```sh
./gradlew runServer --args nogui
```

Aceite a EULA do Minecraft somente se estiver de acordo. O runner de integração já usa `run/integration/eula.txt`, sem afetar uma instalação externa. Processos não sobrevivem necessariamente à restauração do ambiente; inicie novamente quando necessário. A inicialização deve atingir `Done` no log; apenas uma porta aberta não prova prontidão.

## Componentes

- `config/ConfigManager`: validação e cópia de snapshots; o construtor de arquivo único permanece para ler o formato legado.
- `config/SplitConfigStore`: cinco arquivos ativos, migração do legado, backups por arquivo e journal de rollback. O runtime usa o construtor de diretório do ConfigManager.
- `filter/CommandRuleEngine`: política compartilhada por execução, árvore e sugestões; lookup por prefixos de tokens, sem varreduras em ticks.
- `filter/TabSuggestionFilter`: filtros cosméticos por argumento/range, prefixos e snapshot de nomes online; não altera execução.
- `filter/CommandFilter`: cópia filtrada da árvore; não modifica o dispatcher original.
- `security/CommandExecutionGuard`: entrada do jogador e cadeias de redirecionamento.
- `alias/ArgumentTemplate`: padrões com placeholders e projeção dos ranges para a ordem original; a projeção parcial nunca é despachada.
- `alias/ArgumentAliasManager`: extensões aditivas de raízes, detecção de conflitos e execução na árvore original com a mesma fonte.
- `alias/AliasManager`: registro, encaminhamento de argumentos e sugestões.
- `permission/LuckPermsBridge`: carregamento opcional e assinatura de eventos.
- `permission/LuckPermsPermissionService`: consulta contextual oficial, isolada para testes.
- `filter/TabRefreshManager`: retorno à thread do servidor e agendamento de sincronização.
- `command/AdminCommandManager`: sintaxe Brigadier e requisitos de administração.
- `logging/CommandAuditLogger`: fila limitada de gravação, ocultação de argumentos e redução de repetição.
- `mixin/`: pontos mínimos para interceptar rede, construir o pacote e atualizar tabelas de aliases do Brigadier.

`src/integrationTest` é um mod auxiliar exclusivo de desenvolvimento, ausente do jar distribuído. O runner usa a configuração de teste, um jogador simulado e um handler de rede que captura pacotes reais construídos pelo Minecraft. Ele não representa uma sessão cliente autenticada. Testes de API LuckPerms usam proxies de interfaces oficiais; não são um servidor com LuckPerms instalado.

APIs consultadas: fontes Yarn 1.21.1 geradas pelo Loom para CommandManager/ServerPlayNetworkHandler, fontes Fabric API e assinaturas reais da API LuckPerms 5.4. Alterações de mappings exigem rever os descritores dos mixins e repetir a integração dedicada.
