# FocusLock 🛡️ (Bloqueador de Apps por Tag NFC & Horários)

Aplicativo Android nativo (Kotlin) desenvolvido para bloqueio de distrações, controle de foco e combate ao vício em celular.

---

## 🚀 Principais Recursos

1. **Âncora Física via Tag NFC**:
   - Encoste o celular na sua tag NFC de casa para ativar o **Modo Bloqueio Total** instantaneamente.
   - Encoste novamente na tag para **desbloquear**.
   - Permite vincular qualquer tag NFC (adesivos, chaveiros, cartões NFC).

2. **Desbloqueio Manual Protegido por Senha Mestre**:
   - O bloqueio manual pode ser ativado a qualquer momento com 1 toque.
   - Para desbloquear manualmente (sem a tag física), o app exige a **Senha Mestre (PIN)**, evitando recaídas e impulsos.

3. **Blocos de Horário (Estilo ScreenZen)**:
   - Configure janelas automáticas de foco (ex: das 09:00 às 18:00, de Segunda a Sexta).
   - O aplicativo monitora os horários em segundo plano e bloqueia os apps restritos no período estipulado.

4. **Exceções Permanentes de Segurança**:
   - **WhatsApp** (comum e business) permanece sempre liberado.
   - **Discador de emergência / Telefone** permanece sempre liberado.
   - O próprio **FocusLock** permanece liberado (protegido por PIN para alterações).

5. **Motor de Bloqueio em Tempo Real**:
   - Utiliza `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`) para detectar a abertura de apps bloqueados com latência zero e redirecionar para a tela de bloqueio com aviso e desbloqueio.
