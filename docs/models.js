// Model catalog for the website prototype — copied from docs/index.html
// (sourced from the app's ModelInfoProvider.kt). Shared by the home page and
// the models page.
(function () {
const MULTILINGUAL_BROAD = ['en','ar','bg','cs','da','de','el','es','fi','fr','hi','hu','id','it','ja','ko','ms','nl','no','pl','pt','ro','ru','sv','th','tr','uk','vi','zh'];
const LLAMA_LANGS    = ['en','de','fr','it','pt','hi','es','th'];
const PHI_LANGS      = ['ar','zh','cs','da','nl','en','fi','fr','de','he','hu','it','ja','ko','no','pl','pt','ru','es','sv','th','tr','uk'];
const DEEPSEEK_LANGS = ['en','zh'];
const LFM_LANGS      = ['en','ar','zh','fr','de','ja','ko','es'];
const LFM_VL_LANGS   = ['en','ar','zh','fr','de','ja','ko','pt','es'];
const LFM25_LANGS    = ['ar','zh','en','fr','de','hi','id','it','ja','ko','pl','pt','ru','es','th','vi'];
const LFM3_OMNI_LANGS = [...LFM25_LANGS,'da','el','fi','he','nl','sv','tr'];
const SMOLLM3_LANGS  = ['en','fr','es','de','it','pt'];
const MINICPM_LANGS  = ['en','zh'];
const MISTRAL_LANGS  = ['en','fr','de','es','it','pt','nl','zh','ja','ko','ar'];
const GRANITE_LANGS  = ['en','de','es','fr','ja','pt','ar','cs','it','ko','nl','zh'];
const ENGLISH_ONLY   = ['en'];

// Map our website lang codes (mirroring Android resource folders) to the
// ISO codes used in the lang sets above. Only differ for the few legacy
// Android codes; everything else is identity.
const LANG_ISO = { iw:'he', in:'id', nb:'no', fil:'tl' };

// Models data — sourced from app/src/main/java/com/druk/lmplayground/models/ModelInfoProvider.kt
// Sorted by release date (newest first). `tools` mirrors the app's TOOL_CAPABLE set (hammer badge).
// Sizes are Q4_K_M except Qwen 3.5 (Q3_K_M) and GPT-OSS (MXFP4).
const MODELS = [
  // 2026-10-27
  { name:'LFM3 Omni 2B',            provider:'Liquid AI', desc:'Omni-modal vision model',         size:'1.61 GB',  quant:'Q4_K_M', tags:['new','chat'],                 tools:true,  langs:LFM3_OMNI_LANGS },
  { name:'LFM3 1.7B',               provider:'Liquid AI', desc:'Agentic reasoning model',         size:'1.10 GB',  quant:'Q4_K_M', tags:['new','reason','chat'],        tools:true,  langs:LFM25_LANGS },
  // 2026-10-19
  { name:'Spark-X2.6 4B',           provider:'XHToken',   desc:'Hybrid reasoning model',          size:'3.54 GB',  quant:'Q4_K_M', tags:['new','reason','chat'],        tools:true,  langs:MULTILINGUAL_BROAD },
  // 2026-09-06
  { name:'MiniCPM5 2B',             provider:'OpenBMB',   desc:'Hybrid reasoning model',          size:'1.56 GB',  quant:'Q4_K_M', tags:['new','reason','chat'],        tools:true,  langs:MINICPM_LANGS },
  // 2026-08-25
  { name:'Granite 4.2 3B',          provider:'IBM',       desc:'Hybrid reasoning model',          size:'2.24 GB',  quant:'Q4_K_M', tags:['new','reason','chat'],        tools:true,  langs:GRANITE_LANGS },
  // 2026-08-19
  { name:'LFM2.5 1.2B',             provider:'Liquid AI', desc:'Compact chat model',              size:'697 MB',   quant:'Q4_K_M', tags:['new','small','chat'],         tools:true,  langs:LFM25_LANGS },
  // 2026-08-12
  { name:'LFM2.5 VL 3B',            provider:'Liquid AI', desc:'Vision and OCR model',            size:'2.25 GB',  quant:'Q4_K_M', tags:['new','chat'],                 tools:true,  langs:LFM25_LANGS },
  // 2026-07-28
  { name:'LFM2.5 2.6B',             provider:'Liquid AI', desc:'Reasoning model',                 size:'1.67 GB',  quant:'Q4_K_M', tags:['new','reason'],               tools:true,  langs:LFM25_LANGS },
  // 2026-06-03
  { name:'Gemma 4 12B',              provider:'Google',    desc:'Advanced with thinking',          size:'7.38 GB',  quant:'Q4_K_M', tags:['reason','chat'],         tools:true,  langs:MULTILINGUAL_BROAD },
  // 2026-05-21
  { name:'MiniCPM5 1B',             provider:'OpenBMB',   desc:'Hybrid reasoning model',          size:'688 MB',   quant:'Q4_K_M', tags:['new','reason','small','chat'], tools:true,  langs:MINICPM_LANGS },
  // 2026-05-01
  { name:'Granite 4.1 8B',           provider:'IBM',       desc:'Advanced enterprise model',       size:'5.35 GB',  quant:'Q4_K_M', tags:['chat'],                  tools:true,  langs:GRANITE_LANGS },
  // 2026-04-08
  { name:'LFM2.5 VL 450M',          provider:'Liquid AI', desc:'Tiny vision model',               size:'332 MB',   quant:'Q4_K_M', tags:['new','small','chat'],         tools:true,  langs:LFM_VL_LANGS },
  // 2026-03-25
  { name:'Gemma 4 E4B',              provider:'Google',    desc:'Efficient with thinking',         size:'4.98 GB',  quant:'Q4_K_M', tags:['reason','chat'],         tools:true,  langs:MULTILINGUAL_BROAD },
  { name:'Gemma 4 E2B',              provider:'Google',    desc:'Efficient with thinking',         size:'3.11 GB',  quant:'Q4_K_M', tags:['reason','chat'],         tools:true,  langs:MULTILINGUAL_BROAD },
  { name:'LFM2.5 350M',              provider:'Liquid AI', desc:'Ultra-lightweight chat',          size:'267 MB',   quant:'Q4_K_M', tags:['small','chat'],          tools:true,  langs:LFM_LANGS },
  // 2026-02-27
  { name:'Qwen 3.5 4B',              provider:'Alibaba',   desc:'General-purpose with thinking',   size:'2.25 GB',  quant:'Q3_K_M', tags:['reason','chat'],         tools:true,  langs:MULTILINGUAL_BROAD },
  { name:'Qwen 3.5 2B',              provider:'Alibaba',   desc:'General-purpose with thinking',   size:'1.07 GB',  quant:'Q3_K_M', tags:['reason','chat'],         tools:true,  langs:MULTILINGUAL_BROAD },
  { name:'Qwen 3.5 0.8B',            provider:'Alibaba',   desc:'Lightweight with thinking',       size:'466 MB',   quant:'Q3_K_M', tags:['reason','small','chat'], tools:true,  langs:MULTILINGUAL_BROAD },
  // 2026-01-05
  { name:'LFM2.5 VL 1.6B',          provider:'Liquid AI', desc:'Compact vision model',            size:'1.31 GB',  quant:'Q4_K_M', tags:['new','chat'],                 tools:true,  langs:LFM_LANGS },
  // 2025-12-15
  { name:'Nemotron 3 Nano 4B',       provider:'NVIDIA',    desc:'Hybrid reasoning model',          size:'2.84 GB',  quant:'Q4_K_M', tags:['reason'],                      tools:true,  langs:ENGLISH_ONLY },
  // 2025-08-05
  { name:'GPT-OSS 20B',              provider:'OpenAI',    desc:'Large reasoning MoE',             size:'12.11 GB', quant:'MXFP4',  tags:['reason','chat'],               tools:true,  langs:MULTILINGUAL_BROAD },
  // 2025-07-08
  { name:'SmolLM3 3B',              provider:'Hugging Face', desc:'Hybrid reasoning model',          size:'1.92 GB',  quant:'Q4_K_M', tags:['new','reason','chat'],        tools:true,  langs:SMOLLM3_LANGS },
  // 2025-05-14
  { name:'Gemma 3n E4B',             provider:'Google',    desc:'Efficient with thinking',         size:'4.24 GB',  quant:'Q4_K_M', tags:['reason','chat'],               tools:false, langs:MULTILINGUAL_BROAD },
  { name:'Gemma 3n E2B',             provider:'Google',    desc:'Efficient with thinking',         size:'2.79 GB',  quant:'Q4_K_M', tags:['reason','chat'],               tools:false, langs:MULTILINGUAL_BROAD },
  // 2025-04-29
  { name:'Qwen 3 4B',                provider:'Alibaba',   desc:'General-purpose with thinking',   size:'2.5 GB',   quant:'Q4_K_M', tags:['reason','chat'],               tools:true,  langs:MULTILINGUAL_BROAD },
  { name:'Qwen 3 1.7B',              provider:'Alibaba',   desc:'General-purpose with thinking',   size:'1.28 GB',  quant:'Q4_K_M', tags:['reason','chat'],               tools:true,  langs:MULTILINGUAL_BROAD },
  { name:'Qwen 3 0.6B',              provider:'Alibaba',   desc:'Lightweight with thinking',       size:'484 MB',   quant:'Q4_K_M', tags:['reason','small','chat'],       tools:true,  langs:MULTILINGUAL_BROAD },
  // 2025-03-12
  { name:'Gemma 3 4B',               provider:'Google',    desc:'Instruction-tuned with thinking', size:'2.49 GB',  quant:'Q4_K_M', tags:['reason','chat'],               tools:false, langs:MULTILINGUAL_BROAD },
  { name:'Gemma 3 1B',               provider:'Google',    desc:'Lightweight with thinking',       size:'806 MB',   quant:'Q4_K_M', tags:['reason','small','chat'],       tools:false, langs:MULTILINGUAL_BROAD },
  // 2025-02-26
  { name:'Granite 4.0 H-Tiny',       provider:'IBM',       desc:'Hybrid enterprise model',         size:'4.23 GB',  quant:'Q4_K_M', tags:['chat'],                        tools:true,  langs:GRANITE_LANGS },
  { name:'Granite 4.0 Micro',        provider:'IBM',       desc:'Enterprise chat model',           size:'2.1 GB',   quant:'Q4_K_M', tags:['chat'],                        tools:true,  langs:GRANITE_LANGS },
  // 2025-01-20
  { name:'DeepSeek R1 Distill 7B',   provider:'DeepSeek',  desc:'Advanced reasoning distill',      size:'4.68 GB',  quant:'Q4_K_M', tags:['reason'],                      tools:false, langs:DEEPSEEK_LANGS },
  { name:'DeepSeek R1 Distill 1.5B', provider:'DeepSeek',  desc:'Compact reasoning distill',       size:'1.12 GB',  quant:'Q4_K_M', tags:['reason'],                      tools:false, langs:DEEPSEEK_LANGS },
  // 2025-01-15
  { name:'Phi-4 mini',               provider:'Microsoft', desc:'Compact reasoning model',         size:'2.49 GB',  quant:'Q4_K_M', tags:['reason'],                      tools:true,  langs:PHI_LANGS },
  // 2024-12-17
  { name:'Ministral 3 8B Reasoning', provider:'Mistral',   desc:'Advanced reasoning',              size:'5.2 GB',   quant:'Q4_K_M', tags:['reason'],                      tools:true,  langs:MISTRAL_LANGS },
  { name:'Ministral 3 8B Instruct',  provider:'Mistral',   desc:'General-purpose chat',            size:'5.2 GB',   quant:'Q4_K_M', tags:['chat'],                        tools:true,  langs:MISTRAL_LANGS },
  { name:'Ministral 3 3B Reasoning', provider:'Mistral',   desc:'Lightweight reasoning',           size:'2.15 GB',  quant:'Q4_K_M', tags:['reason'],                      tools:true,  langs:MISTRAL_LANGS },
  { name:'Ministral 3 3B Instruct',  provider:'Mistral',   desc:'Lightweight chat',                size:'2.15 GB',  quant:'Q4_K_M', tags:['chat'],                        tools:true,  langs:MISTRAL_LANGS },
  // 2024-09-25
  { name:'Llama 3.2 3B',             provider:'Meta',      desc:'Instruction-tuned chat',          size:'2.02 GB',  quant:'Q4_K_M', tags:['chat'],                        tools:true,  langs:LLAMA_LANGS },
  { name:'Llama 3.2 1B',             provider:'Meta',      desc:'Lightweight instruction',         size:'808 MB',   quant:'Q4_K_M', tags:['small','chat'],                tools:true,  langs:LLAMA_LANGS },
];

const VISION_MODELS = new Set([
  'LFM3 Omni 2B', 'LFM2.5 VL 3B', 'LFM2.5 VL 1.6B', 'LFM2.5 VL 450M',
  'Gemma 4 E4B', 'Gemma 4 E2B', 'Qwen 3.5 4B', 'Qwen 3.5 2B', 'Qwen 3.5 0.8B',
  'Gemma 3 4B', 'Ministral 3 8B Reasoning', 'Ministral 3 8B Instruct',
  'Ministral 3 3B Reasoning', 'Ministral 3 3B Instruct',
]);
const LOGOS = {
  'Liquid AI': 'liquid', 'XHToken': 'xhtoken', 'OpenBMB': 'openbmb', 'IBM': 'ibm', 'Google': 'google',
  'Alibaba': 'qwen', 'NVIDIA': 'nvidia', 'OpenAI': 'openai', 'Hugging Face': 'huggingface',
  'DeepSeek': 'deepseek', 'Microsoft': 'microsoft', 'Mistral': 'mistral', 'Meta': 'meta'
};
// Hugging Face downloads of each model's GGUF repos (the ones the app pulls),
// summed per model, last 30 days, checked 2026-11-21. Drives "Most popular".
const DOWNLOADS = {
  'Gemma 4 E4B': 2114238, 'Gemma 4 12B': 1318794, 'Spark-X2.6 4B': 948632, 'MiniCPM5 2B': 662919,
  'Gemma 4 E2B': 583011, 'LFM3 Omni 2B': 542041, 'GPT-OSS 20B': 447190, 'LFM3 1.7B': 430243,
  'Granite 4.2 3B': 272204, 'Gemma 3 1B': 271031, 'LFM2.5 350M': 228670, 'LFM2.5 1.2B': 218200,
  'Qwen 3.5 4B': 125840, 'Gemma 3 4B': 94698, 'LFM2.5 2.6B': 87666, 'SmolLM3 3B': 76663,
  'Qwen 3.5 2B': 53039, 'Qwen 3.5 0.8B': 47671, 'Llama 3.2 3B': 44611, 'LFM2.5 VL 1.6B': 40152,
  'MiniCPM5 1B': 36293, 'LFM2.5 VL 3B': 28365, 'Ministral 3 3B Instruct': 27009, 'Nemotron 3 Nano 4B': 23959,
  'LFM2.5 VL 450M': 23241, 'DeepSeek R1 Distill 7B': 18030, 'Qwen 3 1.7B': 17758, 'Phi-4 mini': 13370,
  'Qwen 3 4B': 13198, 'Llama 3.2 1B': 8800, 'Gemma 3n E4B': 7521, 'DeepSeek R1 Distill 1.5B': 7337,
  'Ministral 3 8B Instruct': 6309, 'Qwen 3 0.6B': 5975, 'Granite 4.0 H-Tiny': 5675,
  'Ministral 3 8B Reasoning': 3245, 'Gemma 3n E2B': 3033, 'Ministral 3 3B Reasoning': 2551,
  'Granite 4.1 8B': 2352, 'Granite 4.0 Micro': 1263
};
const toMB = (s) => { const n = parseFloat(s); return /GB/.test(s) ? n * 1024 : n; };
MODELS.forEach(function (m) {
  m.vision = VISION_MODELS.has(m.name);
  m.reason = m.tags.indexOf('reason') >= 0;
  m.isNew = m.tags.indexOf('new') >= 0;
  m.mb = toMB(m.size);
  m.dl = DOWNLOADS[m.name] || 0;
  m.logo = LOGOS[m.provider] ? LOGOS[m.provider] + (m.provider === 'OpenAI' ? '.svg' : '.png') : null;
});
window.LMP_MODELS = MODELS;
})();
