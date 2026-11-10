# Changelog

## 1.0.0

Correção do build de testes: preserva barras nativas de comandos como `//wand`, distingue entrada do chat dos nós Brigadier e corrige offsets de sugestões de aliases com `/`.

Novo filtro opcional `tabFilters` para ocultar valores de argumentos por prefixo e posição, por exemplo `impactor:` no terceiro argumento de `/pay`, sem alterar a execução.

Novo `onlinePlayersOnly` opcional em `tabFilters`: interseção das sugestões originais com jogadores conectados, comparação sem distinguir caixa, metadados preservados e escopo pelo range do argumento. Mantém compatibilidade com filtros por prefixo e não altera a execução.

Novo `hideAll` em `tabFilters` para deixar uma posição sem sugestões, inclusive jogadores online, sem interferir nas posições anteriores ou na execução manual.

Novo `argumentAliases` genérico com placeholders nomeados, reorganização de argumentos, extensão aditiva de raízes existentes, sugestões do destino, permissões originais e integração com os filtros de TAB. `list` e `remove` também tratam os novos IDs.

Os rótulos de argumentos dos templates agora usam os nomes configurados em vez de `template_arguments`; `argumentLabel` permite personalização sem alterar a execução.

Configuração ativa dividida em cinco arquivos na pasta `config/shirokai-commands/`, com migração automática do JSON legado preservado, validação conjunta e recuperação transacional de salvamentos interrompidos. Suporte genérico a argumento padrão em raiz existente sem argumentos ambíguos, como `home {nome}` → `home tp {nome}`, preservando subcomandos originais.

Implementação inicial para Fabric 1.21.1 / Java 21:

- Políticas HIDE, BLOCK, REPLACE e ALLOW, whitelist e permissões por regra.
- Aliases com fonte original e sugestões Brigadier.
- Filtragem da árvore enviada ao cliente e bloqueio de comandos recebidos.
- Integração opcional com API LuckPerms 5.4 e atualização por eventos.
- Comandos administrativos, JSON atômico com backup e auditoria assíncrona.
- Testes unitários, integração em servidor dedicado e workflows de build/release.

Ainda não há release publicada. Consulte `docs/VALIDATION.md` antes de criar a tag.
