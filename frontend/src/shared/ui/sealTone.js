// SealBadge 语气(tone)映射(纯逻辑,可测)。
// 合同 §3.1:Critical/High/Medium/Low 同时使用文本、图标(印记字)和
// 印记形状,颜色不能作为唯一编码。
// 合同 §5 SealBadge:朱砂只用于 Critical、主动作或最终确定状态;
// 普通标签用墨线/冷青,避免"满页红章"——因此 High 用 amber、
// Medium 用墨线,与原型风险条里"高"沿用朱砂的写法相比收紧一档,
// 以合同文字为准(见 impl-notes-step5.md 偏离说明)。

export const SEAL_TONES = Object.freeze({
  critical: { className: 'tone-critical', glyph: '危', shape: 'square' },
  high: { className: 'tone-high', glyph: '高', shape: 'circle' },
  medium: { className: 'tone-medium', glyph: '中', shape: 'circle' },
  low: { className: 'tone-low', glyph: '低', shape: 'circle' },
  running: { className: 'tone-running', glyph: '行', shape: 'circle' },
  info: { className: 'tone-info', glyph: '注', shape: 'circle' },
  success: { className: 'tone-success', glyph: '成', shape: 'circle' },
  neutral: { className: 'tone-neutral', glyph: '记', shape: 'circle' },
})

/**
 * @param {string} tone
 * @returns {{ className: string, glyph: string, shape: string }} 未知语气回落 neutral
 */
export function resolveSealTone(tone) {
  return SEAL_TONES[tone] || SEAL_TONES.neutral
}
