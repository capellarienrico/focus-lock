# FocusLock 🛡️ (Bloqueador de Apps por Tag NFC & Horários)

Aplicativo Android nativo (Kotlin) desenvolvido para bloqueio de distrações, controle de foco e combate ao vício em celular.

---

## 🚀 Principais Recursos

1. **Âncora Física via Tag NFC**:
   - Encoste o celular na sua tag NFC de casa para ativar o **Modo Bloqueio Total** instantaneamente.
   - Encoste novamente na tag para **desbloquear**.
   - Permite vincular qualquer tag NFC (adesivos, chaveiros, cartões NFC).

2. **Desbloqueio Manual Protegido por Senha Mestre Alfanumérica**:
   - Na primeira vez que você abre o app, o FocusLock solicita prioritariamente a criação da sua **Senha Mestre** (suportando letras, números e caracteres especiais).
   - O bloqueio manual pode ser ativado a qualquer momento com 1 toque.
   - Para desbloquear manualmente (sem a tag física), o app exige a **Senha Mestre**, evitando recaídas e impulsos.

3. **Múltiplos Blocos de Horário (Estilo ScreenZen)**:
   - Crie, edite e exclua quantos blocos de horário quiser (ex: Trabalho das 09:00 às 18:00, Noite das 22:00 às 06:00) escolhendo os dias da semana de cada bloco.
   - O aplicativo monitora os horários em segundo plano e bloqueia os apps restritos no período estipulado.

4. **Exceções Permanentes de Segurança**:
   - **WhatsApp** (comum e business) permanece sempre liberado.
   - **Aplicativos de Banco** (Nubank, Itaú, Bradesco, BB, Inter, etc.) permanecem sempre liberados.
   - **Discador de emergência / Telefone** permanece sempre liberado.
   - O próprio **FocusLock** permanece liberado (protegido por Senha Mestre para alterações).

5. **Motor de Bloqueio em Tempo Real (Compatível com Bancos)**:
   - Utiliza `UsageStatsManager` (Acesso ao Uso) + `WindowManager` (`TYPE_APPLICATION_OVERLAY`) em vez de `AccessibilityService`, bloqueando instantaneamente apps restritos no Android 8 a 16 sem conflitar com o antifraude de aplicativos bancários e sem sofrer bloqueios de *Background Activity Launch (BAL)*.

---

## 📲 Como Instalar e Configurar

1. Baixe o **`FocusLock.apk`** mais recente na aba [**Releases**](https://github.com/capellarienrico/focus-lock/releases/latest) *(caso tenha uma versão antiga instalada, desinstale-a antes)*.
2. Abra o app e defina sua **Senha Mestre**.
3. Conceda as permissões exibidas no topo do app:
   - **Acesso ao Uso de Apps** (para identificar qual app foi aberto).
   - **Sobrepor a Outros Apps** (para exibir o escudo de bloqueio imediatamente sobre o app restrito).
   - **Sem Restrição de Bateria** *(recomendado para manter o serviço ativo em segundo plano)*.
