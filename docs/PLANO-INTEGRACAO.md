# Plano exploratório: NVIDIA BNR no NexusCut

**Proposta, não implementação.** A branch `feature/capcraft-base` não tem BNR integrado, e este documento não conecta o app a nenhum serviço. A possibilidade de uso da NVIDIA para redução de ruído deve ser decidida e validada antes de qualquer trabalho de produto. O protótipo abaixo é de outra base de aplicativo: não presumir que seus componentes, cofre ou testes já existam no CapCraft nem simplesmente mesclar branches.

## Referência e limites do protótipo separado

A [documentação do protótipo `feature/nvidia-bnr-prototype`](https://github.com/F1N0ZENIX/NexusCut/blob/feature/nvidia-bnr-prototype/docs/nvidia-bnr-prototype.md) descreve seleção de WAV PCM 16-bit mono, em 16 ou 48 kHz, com limite temporário de **512 KiB incluindo cabeçalho**. Arquivos maiores são rejeitados, não divididos. O envio é direto ao preview NVIDIA via gRPC/TLS, com confirmação específica; não extrai áudio do vídeo, não insere resultado na timeline e não salva projeto. Entrada e saída ficam em memória, ainda sem backpressure; a saída vai ao cache privado e pode ser compartilhada separadamente. Rotação ou saída cancelam, mas não desfazem dados já enviados. A verificação de taxa e número de amostras não prova qualidade ou alinhamento amostra a amostra.

O protótipo recomenda dados sintéticos e chave de teste até validar build, segurança e dispositivo. A [documentação do gateway nessa branch](https://github.com/F1N0ZENIX/NexusCut/blob/feature/nvidia-bnr-prototype/docs/ai-gateway.md) também descreve limitações do cofre e ausência de auditoria completa de logs. Não considerar esse protótipo pronto para produção nem afirmar que foi validado ponta a ponta nesta branch.

## Desenho proposto, sujeito a revisão

1. Definir um gateway **dentro do app**, isolado do editor e independente do provedor. O adaptador BNR seria opcional; não inicializar rede nem enviar mídia automaticamente.
2. Após seleção do trecho pelo usuário, extrair uma cópia do áudio do vídeo e convertê-la localmente para **WAV PCM assinado de 16 bits, mono**, em taxa aceita pelo contrato efetivamente vigente (o protótipo usa 16 ou 48 kHz). Validar duração, tamanho, canais, cabeçalho e amostras antes de qualquer envio. Preservar o original; definir explicitamente mapeamento de tempo e tratamento de estéreo/multicanal, reamostragem e arquivos sem áudio.
3. Mostrar destino, trecho e dados enviados, processamento na nuvem, riscos de privacidade, condições/custos e limites aplicáveis. Exigir **consentimento explícito a cada envio**, sem caixas pré-marcadas; obter permissão apropriada de quem aparece ou fala antes de enviar dados de terceiros. Cancelar não recupera bytes já recebidos pelo provedor. Consultar as políticas do serviço, retenção e termos atuais; não prometer gratuidade ou exclusão remota.
4. Só após consentimento, enviar por canal autenticado e criptografado conforme contrato vigente. Não embutir credenciais no código, APK, repositório, documentação ou logs. Projetar armazenamento protegido de chave fornecida pelo usuário, rotação e exclusão, revisando riscos do Keystore e de dados em memória; nenhum armazenamento é garantia absoluta. Não registrar áudio, chaves ou respostas sensíveis.
5. Validar a resposta, formato, duração e alinhamento; não assumir equivalência pelo número de amostras. Criar resultado separado, com prévia e escolha do usuário para aplicar à timeline, mantendo original e possibilidade de desfazer. Definir destino e limpeza de temporários, limites de retenção e comportamento após cancelamento ou morte do processo; limpeza não é exclusão segura.

Antes de tratar trechos longos, desenhar limites de duração/tamanho, particionamento e emenda sem cortes audíveis, backpressure e uso de memória. Não presumir que dividir arquivos já esteja implementado ou que os limites do preview permaneçam iguais. Avaliar alternativa local antes de propor envio à nuvem.

## Testes e decisões pendentes

- Confirmar contrato e disponibilidade na [página NVIDIA BNR](https://build.nvidia.com/nvidia/bnr/api) e [documentação oficial](https://docs.nvidia.com/nim/maxine/bnr/latest/advanced-usage.html), incluindo formatos, versão, limites, autenticação, custos e políticas.
- Com servidor falso e áudio sintético: testar conversão, WAV malformado, múltiplas taxas/canais, silêncio, arquivos grandes, respostas truncadas ou deslocadas, concatenação, qualidade e sincronização.
- Testar consentimento, ausência/erro de chave, rede indisponível, timeout, concorrência, cancelamento em cada fase, rotação, morte de processo, memória e limpeza de temporários; inspecionar manifesto, logs e armazenamento para evitar vazamento. Testes falsos não comprovam funcionamento do serviço real.
- Só testar o serviço real com autorização, dados não sensíveis e conta apropriada; depois validar em aparelhos Android, timeline, desfazer, reprodução e exportação. A integração depende de revisão de privacidade e segurança e de uma decisão separada de implementação.
