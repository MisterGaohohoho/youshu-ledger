'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 try{for(const width of [320,393]){
  const page=await browser.newPage({viewport:{width,height:852},reducedMotion:'reduce'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',route=>route.abort());
  await page.addInitScript(()=>{
   window.failPrivacySave=false;window.privacyWrites=0;
   const at=Date.now(),rows=[{id:1,merchant:'合成午餐',category:'餐饮',amountCents:12345,direction:'EXPENSE'},{id:2,merchant:'合成收入',category:'工资',amountCents:543210,direction:'INCOME'}].map(r=>({...r,uid:'synthetic-'+r.id,note:'',occurredAt:at,source:'MANUAL',state:'POSTED'}));
   window.LedgerNative={remember(){},unifiedSnapshot(text){const q=JSON.parse(text);return JSON.stringify({ok:true,records:rows.filter(r=>!q.direction||r.direction===q.direction),accounts:[{id:1,uid:'wallet',name:'合成钱包',kind:'cash',type:'ASSET',amountCents:88888}],channels:{ocr:{enabled:false}},automatic:{applications:[]},monthlyBudgetCents:99999,summaryAmountsHidden:localStorage.getItem('synthetic-summary-hidden')==='true',ledgerView:{summary:{totals:{income:543210,expense:12345,incomeCount:1,expenseCount:1},previous:{income:10000,expense:10000}}}})},
    setSummaryAmountsHidden(hidden){privacyWrites++;if(failPrivacySave)return JSON.stringify({ok:false,error:'合成保存失败'});localStorage.setItem('synthetic-summary-hidden',String(hidden));return JSON.stringify({ok:true,summaryAmountsHidden:hidden})}
   };
  });
  await page.goto(pathToFileURL(path.resolve(process.env.LEDGER_WEB_ROOT||path.join(__dirname,'../app/src/main/assets/web'),'index.html')).href);
  assert.match(await page.locator('.balance-number').textContent(),/123\.45/);
  const count=await page.locator('#home-count').textContent(),trend=await page.locator('#home-change').textContent();
  await page.evaluate(()=>failPrivacySave=true);await page.locator('#hide-summary').click();
  assert.equal(await page.locator('#hide-summary').getAttribute('aria-pressed'),'false');assert.match(await page.locator('.balance-number').textContent(),/123\.45/);
  await page.evaluate(()=>failPrivacySave=false);await page.locator('#hide-summary').click();
  assert.equal(await page.locator('#home-count').textContent(),count);assert.equal(await page.locator('#home-change').textContent(),trend);
  assert.match(await page.locator('.entry-amount').first().textContent(),/123\.45/,'Bill amounts remain visible');
  const masked=async()=>{
   assert.equal(await page.locator('.balance-number').textContent(),'****');
   assert.equal(await page.locator('#hide-summary').getAttribute('aria-label'),'显示统计金额');
   assert.equal(await page.locator('#hide-summary').getAttribute('aria-pressed'),'true');
   assert(await page.locator('#hide-summary .fa-eye-slash').count());
   const card=await page.locator('.balance').evaluate(e=>e.textContent+' '+[e,...e.querySelectorAll('*')].flatMap(node=>['aria-label','aria-valuenow','aria-valuemax','aria-valuetext','title','value'].map(name=>node.getAttribute(name)||'')).join(' '));
   assert(!/123\.45|5,432\.1|5,308\.65|999\.99|876\.54|12345|543210|99999/.test(card),'Hidden values must not remain in card text or accessibility attributes: '+card);
  };
  await masked();
  assert.equal(await page.locator('.budget-label').textContent(),'月限额 ****');assert.equal(await page.locator('.budget-status').textContent(),'剩余 ****');
  assert.equal(await page.locator('.budget-meter').getAttribute('aria-valuemax'),'100');
  for(const mode of ['all','income','expense']){
   await page.locator(`[data-filter="${mode}"]`).click();await masked();
   if(mode==='all')assert.deepEqual(await page.locator('#home-combined dd').allTextContents(),['****','****']);
  }
  for(const scope of ['all','2025','2026-11-01..2026-11-23',await page.evaluate(()=>localMonth(Date.now()))]){
   await page.evaluate(scope=>{month=scope;refresh()},scope);await masked();
  }
  await page.evaluate(()=>{MonthlyBudget.update(10000);render()});await masked();assert.equal(await page.locator('.budget-status').textContent(),'已超 ****');
  await page.evaluate(()=>{assetsHidden=true;go('assets')});assert.equal(await page.locator('#net-amount').textContent(),'****');
  await page.locator('#hide-assets').click();assert.notEqual(await page.locator('#net-amount').textContent(),'****');
  await page.evaluate(()=>go('ledger'));await masked();
  await page.reload();await masked();assert.equal(await page.evaluate(()=>privacyWrites),0,'Reload must restore the preference without rewriting it');
  if(process.env.UI_SCREENSHOTS){fs.mkdirSync(process.env.UI_SCREENSHOTS,{recursive:true});await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`summary-hidden-${width}.png`)})}
  await page.locator('#hide-summary').focus();await page.keyboard.press('Space');
  assert.match(await page.locator('.balance-number').textContent(),/123\.45/);assert.equal(await page.locator('#hide-summary').getAttribute('aria-label'),'隐藏统计金额');
  assert.match(await page.locator('.budget-label').textContent(),/999\.99/);assert.equal(await page.locator('.budget-meter').getAttribute('aria-valuemax'),'99999');
  await page.reload();assert.match(await page.locator('.balance-number').textContent(),/123\.45/);
  await page.evaluate(()=>{LedgerUI.event('fontScale',1.5);const sizes=[...document.querySelectorAll('#ledger *')].map(e=>[e,parseFloat(getComputedStyle(e).fontSize)]);for(const [e,size] of sizes)e.style.fontSize=(size*1.5)+'px'});
  for(const mode of ['expense','all','income']){
   await page.locator(`[data-filter="${mode}"]`).click();await page.locator('#hide-summary').click();
   const layout=await page.evaluate(()=>[...document.querySelectorAll('.balance-heading,#hide-summary,.balance-number,#home-combined dd,#home-count,.summary-trend,#monthly-budget')].filter(e=>e.getClientRects().length).map(e=>{const r=e.getBoundingClientRect();return {id:e.id||e.className,left:r.left,right:r.right,width:r.width,height:r.height,client:e.clientWidth,scroll:e.scrollWidth}}));
   assert(layout.every(r=>r.left>=0&&r.right<=width&&r.scroll<=r.client+1),JSON.stringify(layout));
   const button=layout.find(r=>r.id==='hide-summary');assert(button.width>=44&&button.height>=44);
  }
  if(process.env.UI_SCREENSHOTS)await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`summary-large-${width}.png`)});
  assert.deepEqual(errors,[]);console.log(`PASS summary privacy ${width}: persistence, modes, budget, accessible labels, bill scope and large text`);await page.close();
 }}finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
