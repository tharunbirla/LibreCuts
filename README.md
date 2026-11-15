# NexusCut — base experimental CapCraft

O NexusCut é um projeto experimental de editor de vídeo para Android. Esta branch, [`feature/capcraft-base`](https://github.com/F1N0ZENIX/NexusCut/tree/feature/capcraft-base), parte do [CapCraft para Android](https://github.com/artcraft-android/capcraft-android) e de seu motor [Concat](https://github.com/jub0t/concat), com alterações próprias. A migração e sua origem estão documentadas em [PROVENANCE.md](PROVENANCE.md); a `main` e as outras branches têm histórias diferentes. O projeto não é afiliado à ByteDance ou ao CapCut.

## Estado e recursos

- **Código herdado:** a base CapCraft inclui editor com linha do tempo, cortes e divisão de clipes, camadas, texto, efeitos e filtros, recursos de áudio, legendas e exportação. São recursos descritos pelo upstream, não uma garantia de que cada fluxo já foi validado nesta branch.
- **Alterações nesta branch:** há identidade visual NexusCut em partes do app e opções para formatar legendas/letras novas em maiúsculas e remover pontuação selecionada. A formatação acontece após a segmentação; não equivale a testar reconhecimento, sincronização e exportação completos.
- **Verificado no CI:** o [run do commit `75a5234`](https://github.com/F1N0ZENIX/NexusCut/actions/runs/43771357457) concluiu os testes de formatação de legendas e a build de APK debug arm64. Esse resultado se refere àquele commit, não comprova que todos os recursos funcionem ou que builds posteriores passem. Consulte [Actions](https://github.com/F1N0ZENIX/NexusCut/actions) para o resultado do commit desejado.
- **Pendente de validação:** instalação e uso em aparelho, edição, modelos de reconhecimento, legendas na timeline, renderização, SRT e exportação ponta a ponta. O comportamento pode variar conforme aparelho, mídia e modelo.
- **NVIDIA BNR:** não está integrada nesta branch. Existe um [protótipo isolado em outra branch](https://github.com/F1N0ZENIX/NexusCut/blob/feature/nvidia-bnr-prototype/docs/nvidia-bnr-prototype.md), baseado em outra árvore, que não integra o áudio ao editor. Uma possível integração é apenas proposta em [docs/PLANO-INTEGRACAO.md](docs/PLANO-INTEGRACAO.md).

Não há promessa de abrir ou migrar projetos das versões antigas do NexusCut ou de outros editores. Faça cópias dos arquivos e projetos antes de testar.

## Build debug arm64

O [workflow](.github/workflows/nexuscut-ci.yml) usa Ubuntu 24.04, JDK 21, Rust fixado por `src/rust-toolchain.toml`, alvo `aarch64-linux-android`, `cargo-ndk` 4.1.2, Android SDK/NDK 29.0.14206865, CMake e libclang. O SDK e as ferramentas de compilação também precisam estar instalados; scripts de preparação baixam/compilam dependências. A receita abaixo reproduz o fluxo de CI em ambiente Linux com essas ferramentas e `ANDROID_HOME` configurado (em Windows, use WSL). É necessário espaço livre suficiente; o README upstream recomenda ao menos 10 GB.

```bash
# Na raiz do repositório; NDK instalado pelo sdkmanager do Android SDK
export NDK_VERSION=29.0.14206865
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export GGML_NATIVE=OFF
(cd src && rustup toolchain install && rustup target add aarch64-linux-android)
cargo install cargo-ndk --version 4.1.2 --locked
src/scripts/ffmpeg-mobile.sh aarch64-linux-android
src/scripts/sherpa-mobile.sh aarch64-linux-android
cd src
eval "$(scripts/mobile-env.sh aarch64-linux-android)"
cd crates/capcraft-android/android
./gradlew assembleDebug -Pcapcraft.abis=arm64-v8a --no-daemon
```

O APK fica em `src/crates/capcraft-android/android/app/build/outputs/apk/debug/`. O CI publica o artefato `capcraft-debug-arm64` quando a build passa. A receita acompanha o workflow; esta alteração de documentação não executa uma nova build local.

## Cuidado ao instalar

O ID base do app ainda é o do CapCraft, `io.github.artcraftandroid.capcraft`. Nesta configuração o **debug adiciona `.debug`**, ficando `io.github.artcraftandroid.capcraft.debug`; por isso, não é o mesmo ID da variante release normal. Outra instalação debug com esse ID pode entrar em conflito. Uma build sem esse sufixo pode disputar a instalação com o CapCraft de mesmo ID. Assinaturas debug podem variar entre máquinas/CI e diferir de releases: o Android pode recusar uma atualização mesmo com ID igual. Não desinstale uma instalação existente só para forçar o teste: isso pode apagar seus dados. Verifique ID, assinatura e backups antes de instalar.

## Licença e créditos

Esta base é distribuída sob [GNU AGPL-3.0-or-later](LICENSE). CapCraft para Android é copyright da ArtCraft Team e contribuidores; Concat é copyright de Jareer Abdullah e contribuidores. Fontes: [CapCraft](https://github.com/artcraft-android/capcraft-android) e [Concat](https://github.com/jub0t/concat); o commit upstream usado na migração está em [PROVENANCE.md](PROVENANCE.md). Consulte também [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e os avisos do app para autores, modelos, bibliotecas e licenças específicas. Nada aqui altera as licenças de terceiros.
