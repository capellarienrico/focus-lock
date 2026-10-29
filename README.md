# FocusLock v3.0 🛡️ — Super App de Foco & Minimalismo Digital

Aplicativo Android nativo (Kotlin) completo para **bloqueio real de distrações**, **filtro minimalista de notificações**, **sessões Pomodoro / Modo Monge**, **controle de tempo de tela** e **âncora física via Tag NFC** — projetado para ser **100% compatível com aplicativos de banco** (sem utilizar `AccessibilityService`).

---

## ✨ O que há de novo no FocusLock v3.0 (Super App Edition)

### 1. 🔒 Motor de Bloqueio Real via Sobreposição (`WindowManager Overlay`)
- **Imune às restrições do Android 14, 15 e 16 (Samsung OneUI, Xiaomi HyperOS, Motorola, Pixel)**: exibe instantaneamente uma janela de sobreposição em tela cheia (`TYPE_APPLICATION_OVERLAY`) assim que um app restrito entra em primeiro plano, sem depender de *Background Activity Launch (BAL)*.
- **100% Bank-Safe**: utiliza `UsageStatsManager` + `WindowManager` em vez de `AccessibilityService`, evitando qualquer falso positivo nos sistemas antifraude de bancos (Nubank, Itaú, Bradesco, Inter, BB, etc.).
- **Exceções de Segurança Permanentes**: apps de banco, discador de emergência e launchers nunca são bloqueados; WhatsApp permanece liberado para bloqueio de tela.

### 2. 🔕 Filtro de Notificações Estilo *Minimalist Phone* + Pílula Flutuante
- Selecione os aplicativos barulhentos cujas notificações deseja silenciar e interceptar automaticamente.
- **Pílula Flutuante Arrastável (`🔕 X notificações filtradas`)**: aparece discretamente na **Tela Inicial** (ou sobre todos os apps / apenas na barra de status) indicando quantas notificações foram filtradas.
- **Caixa de Notificações Filtradas**: toque na pílula flutuante ou na notificação silenciosa consolidada para revisar tudo no seu próprio tempo, filtrar por app, tocar para abrir a conversa/notificação original ou limpar tudo com 1 toque.
- **Proteção Inteligente**: chamadas, alarmes, timers, mídia em reprodução e aplicativos de banco nunca são interceptados.

### 3. 🗂️ Seletor Inteligente de Aplicativos (Categorias + Ordenação)
- **Categorização Automática**: Todos, 📱 Redes Sociais, 🎬 Vídeo & Streaming, 🎮 Jogos, 🛍️ Compras & Delivery, 🎵 Música & Áudio, 🌐 Navegadores & Notícias, 💼 Produtividade & Outros.
- **7 Modos de Ordenação**:
  - ⏱️ **Tempo de Tela (Hoje)**
  - 📅 **Tempo de Tela (Últimos 7 dias)**
  - 💾 **Tamanho do App (Maior → Menor)** *(APK + Split APKs)*
  - 🔤 **Nome (A → Z)** e **Nome (Z → A)**
  - 🆕 **Instalados Recentemente**
  - ✅ **Selecionados Primeiro**
- Busca instantânea + botão de **Marcar/Desmarcar todos os visíveis** da categoria filtrada.

### 4. ⏱️ Sessões Rápidas de Foco (Pomodoro) & 🧘 Modo Monge
- Inicie sessões cronometradas com 1 toque (**25 min**, **45 min**, **1h** ou duração personalizada) com desbloqueio automático e alerta ao concluir.
- **Modo Monge em Sessões Cronometradas**: opcionalmente oculta o desbloqueio por Senha Mestre enquanto o cronômetro estiver rodando (apenas a Tag NFC física ou o fim do timer libera).

### 5. ⏳ Limite Diário de Tempo de Tela por App + Orçamento de Sonecas
- Defina uma cota diária de uso (ex: 30 min/dia) para apps específicos — mesmo fora de horários agendados, o FocusLock bloqueia automaticamente ao atingir o limite do dia.
- **Pausa Consciente & Desafio Cognitivo**: respiração guiada antes de abrir sonecas de 1/5/10/15 min ou exigir Senha Mestre, com limite diário de sonecas e proteção anti-desinstalação.

### 6. 📊 Painel de Bem-Estar Digital & Backup JSON
- Resumo na tela principal com **Tempo de Tela Hoje**, **Tentativas Bloqueadas**, **Notificações Filtradas Hoje** e **Top 5 Apps Mais Usados** (com botão de bloquear em 1 toque).
- **Diagnóstico de Saúde do Sistema** em tempo real e **Exportação / Importação completa em JSON** (rotinas, listas de apps e preferências).

---

## 📲 Como Instalar e Configurar

1. Baixe o **`FocusLock.apk`** mais recente em [**GitHub Releases (v3.0)**](https://github.com/capellarienrico/focus-lock/releases/latest) *(se você instalou uma versão anterior com assinatura diferente, desinstale a antiga antes de instalar)*.
2. Abra o app e crie sua **Senha Mestre**.
3. Conceda as permissões indicadas no painel:
   - **Acesso ao Uso de Apps** (detectar app em primeiro plano e calcular tempo de tela).
   - **Sobrepor a Outros Apps** (exibir o escudo de bloqueio instantâneo e a pílula flutuante `X notificações filtradas`).
   - **Acesso a Notificações** *(na aba `🔔 Filtro`, para ativar o Filtro de Notificações Minimalista)*.
   - **Sem Restrição de Bateria** *(recomendado para manter o motor 24/7 em segundo plano)*.
