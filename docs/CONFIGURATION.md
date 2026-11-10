# Configuração em arquivos separados

O diretório ativo é `config/shirokai-commands/`. Na primeira execução desta versão, o conteúdo válido de `config/shirokai-commands.json` é repartido automaticamente; o arquivo antigo permanece intacto, mas deixa de ser lido. Não precisa copiar suas regras manualmente.

## settings.json

```json
{
  "configVersion": 1,
  "settings": {
    "opBypass": true,
    "whitelistEnabled": false,
    "autoRefreshTab": true,
    "luckPermsIntegration": true,
    "logBlockedCommands": true,
    "silentBlockedCommands": false,
    "guardInternalPlayerCommands": false,
    "defaultMode": "allow"
  }
}
```

## messages.json

```json
{
  "messages": {
    "blocked": "§cVocê não tem permissão para usar este comando.",
    "unknown": "§cComando desconhecido.",
    "reload": "§aConfigurações recarregadas com sucesso.",
    "aliasConflict": "§cEste alias já está sendo utilizado."
  }
}
```

## commands.json

Guarda HIDE, BLOCK e ALLOW. Exemplo:

```json
{
  "commands": {
    "calculate": {"mode": "hide", "security": false},
    "/pos1": {"mode": "block", "security": false},
    "kit claim": {"mode": "allow", "security": false}
  }
}
```

A notação interna `/pos1` corresponde a `//pos1` no chat. Comandos criados exclusivamente no cliente continuam fora do controle de um mod apenas servidor.

## aliases.json

`prefixAliases` contém os REPLACE antigos; `argumentAliases` contém os padrões com parâmetros nomeados. Este exemplo supõe que o mod de homes registre `home set` e `home tp`:

```json
{
  "prefixAliases": {
    "home set": {
      "mode": "replace",
      "alias": "set home",
      "security": false
    }
  },
  "argumentAliases": {
    "home_tp": {
      "alias": "home {nome}",
      "target": "home tp {nome}",
      "extendExisting": true,
      "argumentLabel": "nome"
    }
  }
}
```

Use `/home MinhaCasa` para encaminhar `/home tp MinhaCasa`. Os subcomandos originais (`home tp`, `home set`, etc.) e a raiz sem argumentos têm prioridade e não são capturados como nomes. Se a raiz já tiver um argumento próprio, o atalho é recusado para evitar ambiguidade; nesse caso escolha outra raiz. Substituições REPLACE também continuam aplicando suas próprias políticas de visibilidade e execução.

Não copie aliases de comandos que não existem no seu conjunto de mods. Em uma instalação nova, os dois mapas são vazios. Os exemplos completos para pay e homes estão em `config/shirokai-commands.example/`; eles não são ativados automaticamente.

## tab-filters.json

```json
{
  "tabFilters": [
    {"command": "pay", "argumentIndex": 2, "onlinePlayersOnly": true},
    {"command": "pay", "argumentIndex": 3, "hidePrefixes": ["impactor:"]},
    {"command": "pay", "argumentIndex": 4, "hideAll": true}
  ]
}
```

São filtros de sugestões, não bloqueios de execução. Os índices seguem a ordem do comando original, inclusive quando um alias reorganiza argumentos. Os filtros não adicionam nomes omitidos pelo provedor original.

## Aplicar e recuperar

Após trocar o jar, reinicie. Depois de editar os JSONs ativos, use `/sc reload`; todos os arquivos são validados antes de aplicar qualquer alteração. Os arquivos gerados já contêm as seções obrigatórias. Não deixe um dos cinco arquivos faltando e não coloque um REPLACE em `commands.json`: use `prefixAliases` em `aliases.json`. Caminhos duplicados entre os dois arquivos são rejeitados.

Salvamentos administrativos criam `.bak` ao lado de cada arquivo anterior, usam movimentos atômicos e um journal interno para rollback de uma operação incompleta. Arquivos adicionais na pasta são preservados. A recuperação de uma interrupção é automática na próxima leitura; um journal inválido produz erro e não é interpretado como uma lista de caminhos arbitrários. Arquivos inválidos mantêm a configuração válida anterior em memória; se a inicialização falhar, o mod usa política restritiva e permite a recuperação pelo console/admin autorizado.

O legado só serve como origem da primeira migração. Para restaurá-lo deliberadamente, pare o servidor, preserve a pasta ativa fora do caminho configurado, coloque o legado válido em `config/shirokai-commands.json` e reinicie. A pasta será gerada de novo. Nunca remova a pasta sem guardar suas alterações atuais.
