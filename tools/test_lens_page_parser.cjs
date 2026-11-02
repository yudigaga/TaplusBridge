/* Offline browser checks: observed Google card structure plus separately labeled synthetic cases. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const playwrightPath = process.argv[2] || 'playwright';
const {chromium} = require(playwrightPath);
const root = path.resolve(__dirname, '..');
const asset = fs.readFileSync(path.join(root, 'app/src/main/assets/lens/extract.js'), 'utf8');
const fixture = name => fs.readFileSync(path.join(root, 'app/src/test/resources/lens', name), 'utf8');

(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true
  });
  try {
    const page = await browser.newPage();
    let html = '';
    // Every request is fulfilled locally; this check never contacts Google.
    await page.route('**/*', route => route.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: html}));
    const check = async (url, body) => {
      html = body;
      await page.goto(url, {waitUntil: 'load'});
      return page.evaluate(asset + '(' + JSON.stringify('request-A') + ',7)');
    };
    const captcha = await check('https://www.google.com/sorry/index', fixture('google-captcha-observed.html'));
    assert.equal(captcha.status, 'needs_interaction');
    assert.equal(captcha.request_id, 'request-A');
    assert.equal(captcha.page_generation, 7);
    assert.equal((await check('https://consent.google.com/m', '<html></html>')).status, 'needs_interaction');
    assert.equal((await check('https://www.google.com/search?udm=26', '<html><a href="https://example.org"><img src="x">Navigation</a></html>')).status, 'parse_error');
    const data = await check('https://www.google.com/search?udm=26', fixture('schema-itemlist-synthetic.html'));
    assert.equal(data.status, 'parse_error');
    assert.equal(data.parser_version, 'google-visual-cards-20261102');
    assert.equal(data.items.length, 0);
    assert.equal((await check('https://www.google.com/search?udm=26', '<html><script type="application/ld+json">{"@type":"ItemList","numberOfItems":0,"itemListElement":[]}</script></html>')).status, 'parse_error');
    assert.equal((await check('https://www.google.com/search?udm=26', '<html><script type="application/ld+json">{"@type":"SearchResultsPage","mainEntity":{"@type":"ItemList","numberOfItems":0,"itemListElement":[]}}</script></html>')).status, 'parse_error');
    assert.equal((await check('https://www.google.com/search?udm=48&vsrid=session', '<html><body>没有与您的搜索相匹配的内容</body></html>')).status, 'empty');
    assert.equal((await check('https://www.google.com/search?udm=50&vsrid=session', '<html><body>似乎无法针对该搜索生成回答。试试问点别的吧。</body></html>')).status, 'empty');
    assert.equal((await check('https://lens.google.com/', '<html data-taplus-lens-upload="1"></html>')).status, 'loading');
    assert.equal((await check('https://example.org/search?udm=26', fixture('schema-itemlist-synthetic.html'))).status, 'parse_error');
    const observed = await check('https://www.google.com/search?udm=26', fixture('google-visual-card-observed-20261102.html'));
    assert.equal(observed.status, 'results_ready');
    assert.equal(observed.items.length, 1);
    assert.equal(observed.items[0].title, '2026 星城沙县小吃(滨海星城店) (Lincheng New District ...');
    assert.equal(observed.items[0].source_name, 'Trip.com');
    assert.equal(observed.items[0].match_type, 'visual');
    assert.equal(observed.items[0].page_url, 'https://www.google.com/goto?url=CAES_REDACTED_OBSERVED_OPAQUE');
    assert.equal(observed.items[0].link_type, 'google_redirect');
    assert.ok(observed.items[0].thumbnail_url.startsWith('data:image/png;base64,'));
    assert.equal(observed.items[0].image_url, null);
    assert.equal(observed.diagnostics.document_state, 'complete');
    assert.equal(observed.diagnostics.result_root_count, 1);
    assert.equal(observed.diagnostics.visual_button_count, 1);
    assert.equal(observed.diagnostics.source_link_count, 1);
    assert.equal(observed.diagnostics.heading_count, 1);
    assert.equal(observed.parse_reason, 'page_observed');
    const serialized = await page.evaluate('JSON.stringify(' + asset + '(' + JSON.stringify('request-A') + ',7))');
    assert.equal(typeof serialized, 'string');
    assert.deepEqual(JSON.parse(serialized), observed);
    const oversized = fixture('google-visual-card-observed-20261102.html').replace(/data:image\/png;base64,[^"]+/, 'data:image/png;base64,' + 'A'.repeat(131073));
    const bounded = await check('https://www.google.com/search?udm=26', oversized);
    assert.equal(bounded.status, 'results_ready');
    assert.equal(bounded.items[0].thumbnail_url, null);
    const actual = fixture('google-visual-card-observed-20261102.html');
    const doubled = actual.replace('</main>', actual.match(/<main id="center_col">([\s\S]*?)<\/main>/)[1] + '</main>');
    assert.equal((await check('https://www.google.com/search?udm=26', doubled)).items.length, 1);
    const unsafe = actual.replace('/goto?url=CAES_REDACTED_OBSERVED_OPAQUE', 'javascript:alert(1)');
    assert.equal((await check('https://www.google.com/search?udm=26', unsafe)).status, 'parse_error');
    const card = actual.match(/<main id="center_col">([\s\S]*?)<\/main>/)[1];
    const many = '<main id="center_col">' + Array.from({length:50}, (_, i) => card
      .replace('CAES_REDACTED_OBSERVED_OPAQUE', 'CAES_TEST_' + i)
      .replace(/data:image\/png;base64,[^"]+/, 'data:image/png;base64,' + 'A'.repeat(120000))).join('') + '</main>';
    const boundedMany = await check('https://www.google.com/search?udm=26', many);
    assert.equal(boundedMany.items.length, 40);
    assert.ok(JSON.stringify(boundedMany.items).length < 394000);
    assert.ok(boundedMany.items.some(row => row.thumbnail_url === null));
    console.log('16 offline browser parser scenarios passed, including observed visual-card DOM, explicit no-match states, safe marker counts and JSON serialization. Phone WebView/end-to-end remains unverified.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
