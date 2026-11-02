/* Visual card selectors observed in the live Google Lens session on 2026-11-02. */
(function (requestId, pageGeneration) {
  'use strict';
  const version = 'google-visual-cards-20261102';
  const diagnostics = () => ({
    document_state: document.readyState,
    result_root_count: document.querySelectorAll('#center_col').length,
    visual_button_count: document.querySelectorAll('#center_col [data-sv-cpt="4"][role="button"]').length,
    card_count: document.querySelectorAll('#center_col div.N54PNb.BToiNc').length,
    source_link_count: document.querySelectorAll('#center_col a.LBcIee').length,
    heading_count: document.querySelectorAll('#center_col [role="heading"][aria-level="3"]').length
  });
  const result = (status, message, items) => ({
    request_id: requestId, page_generation: pageGeneration,
    parser_version: version, status: status, message: message, items: items || [],
    parse_reason: status === 'parse_error' ? 'layout_unrecognized' : 'page_observed',
    diagnostics: diagnostics()
  });
  const host = location.hostname.toLowerCase();
  if (host === 'consent.google.com' || host === 'accounts.google.com') {
    return result('needs_interaction', 'Google 需要确认或登录，请打开网页完成操作');
  }
  if (!['lens.google.com', 'www.google.com', 'google.com'].includes(host)) {
    return result('parse_error', '当前页面不是受支持的 Google Lens 页面');
  }
  // Both /sorry/index and #captcha-form were captured from an actual Lens redirect.
  if (location.pathname.startsWith('/sorry/') || document.querySelector('form#captcha-form')) {
    return result('needs_interaction', 'Google 要求人工验证，请打开同一网页会话完成操作');
  }
  if (document.documentElement.dataset.taplusLensUpload === '1' || document.getElementById('taplus-lens-upload')) {
    return result('loading', '等待将当前图片交给 Google Lens');
  }
  if (document.readyState !== 'complete') return result('loading', 'Google Lens 页面加载中');

  const query = new URLSearchParams(location.search);
  const visualSearch = (location.pathname === '/search' &&
      (query.get('udm') === '26' || query.has('lns_mode') || query.has('vsrid'))) ||
      (host === 'lens.google.com' && location.pathname === '/search');
  if (!visualSearch) return result('parse_error', '尚未进入可识别的 Lens 结果页，可打开网页检查');

  const http = value => {
    if (typeof value !== 'string' || value.length > 8192) return null;
    try {
      let u = new URL(value, location.href);
      if (['www.google.com', 'google.com'].includes(u.hostname.toLowerCase())) {
        const target = u.pathname === '/url' ? (u.searchParams.get('url') || u.searchParams.get('q')) :
            u.pathname === '/imgres' ? u.searchParams.get('imgrefurl') : null;
        if (target) u = new URL(target);
      }
      if (!['http:', 'https:'].includes(u.protocol) || !u.hostname || u.username || u.password) return null;
      return u.href;
    } catch (_) { return null; }
  };
  const text = value => typeof value === 'string' ? value.trim().slice(0, 1024) : '';
  const thumbnail = value => http(value) || (typeof value === 'string' && value.length <= 131072 &&
      /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(value) ? value : null);
  const items = [], seen = new Set();
  let payloadSize = 0;
  const pushItem = item => {
    if (seen.has(item.page_url) || items.length >= 40) return;
    let size = JSON.stringify(item).length;
    if (payloadSize + size > 393216) { item.thumbnail_url = null; size = JSON.stringify(item).length; }
    if (payloadSize + size > 393216) return;
    payloadSize += size; seen.add(item.page_url); items.push(item);
  };
  const redirect = page => {
    const u = new URL(page);
    return ['www.google.com', 'google.com'].includes(u.hostname.toLowerCase()) &&
        u.pathname === '/goto' && !!u.searchParams.get('url');
  };
  // Scope to visual result cards: preview image and source/heading are siblings in this observed layout.
  Array.from(document.querySelectorAll('#center_col [data-sv-cpt="4"][role="button"]')).slice(0, 80).forEach(visual => {
    const card = visual.closest('div.N54PNb.BToiNc');
    if (!card) return;
    const image = visual.querySelector('.q07dbf.uhHOwf.ez24Df img');
    const link = card.querySelector('a.LBcIee');
    const heading = link && link.querySelector('[role="heading"][aria-level="3"]');
    if (!image || !heading) return;
    const page = http(link.getAttribute('href'));
    if (!page) return;
    const pageHost = new URL(page).hostname.toLowerCase();
    const googleRedirect = redirect(page);
    if ((pageHost === 'google.com' || pageHost.endsWith('.google.com')) && !googleRedirect) return;
    const source = link.querySelector('.R8BTeb');
    pushItem({title: text(heading.textContent), page_url: page,
      thumbnail_url: thumbnail(image.currentSrc || image.getAttribute('src')),
      source_name: text(source && source.textContent), image_url: null,
      match_type: 'visual', link_type: googleRedirect ? 'google_redirect' : 'direct'});
  });
  if (items.length) return result('results_ready', '已提取页面中的图片结果', items);
  const visibleText = (document.body && document.body.innerText || '').slice(0, 12000);
  if (query.get('udm') === '50' && /似乎无法针对.{0,20}搜索生成回答|unable to generate an? (?:AI )?(?:overview|answer)/i.test(visibleText))
    return result('empty', 'Google 当前无法为这张图片生成 AI 回答');
  if (/没有与.{0,30}搜索.{0,20}匹配.{0,20}内容|did not match any documents|no results found/i.test(visibleText))
    return result('empty', 'Google Lens 没有找到与当前图片匹配的内容');
  // Unknown future layouts must fail explicitly rather than becoming empty results.
  return result('parse_error', '当前 Lens 页面结构尚未适配，请打开网页检查或稍后重试');
})
