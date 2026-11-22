/* Import provenance and screenshot policy are stored and validated by native SQLite. */
(()=>{
 'use strict';
 const esc=escapeText;
 const date=at=>at?localTime(at).replace('T',' '):'无新增账单';
 const size=bytes=>`${(Number(bytes||0)/1048576).toLocaleString('zh-CN',{maximumFractionDigits:1})} MiB`;
 function call(name,...args){if(!native?.[name])throw Error('请更新应用后使用此功能');const r=JSON.parse(native[name](...args));if(!r.ok)throw Error(r.error||'操作失败，请重试');return r}
 function attempt(fn){try{return fn()}catch(error){notify(error.message)}}
 function summary(){const target=$('screenshot-storage-summary');if(target)target.textContent=screenshotStorage.maxBytes?`${size(screenshotStorage.bytes)} / ${size(screenshotStorage.maxBytes)}`:'管理占用与容量上限'}
 function history(){attempt(()=>{
  showSheet('导入记录','<p class="sheet-copy">从此版本开始记录导入批次。旧账单可在首页筛选后批量管理。</p><div id="import-history-list"></div><button type="button" class="management-more" id="import-history-more">加载更多</button><button type="button" class="management-back" id="import-history-back">返回账单与备份</button>');
  let offset=0;
  function more(){attempt(()=>{const data=call('importHistory',offset);offset+=data.items.length;
   $('import-history-list').insertAdjacentHTML('beforeend',data.items.map(b=>`<button type="button" class="sheet-row import-batch-row" data-import-batch="${esc(b.uid)}"><span><b>${esc(b.fileName)}</b><small>${esc(b.source)} · ${esc(date(b.importedAt))}</small><small>新增 ${b.created} 笔 · 当前可管理 ${b.remaining} 笔${b.created>0&&!b.remaining?' · 已无可删除账单':''}</small></span><span aria-hidden="true">›</span></button>`).join(''));
   if(!offset)$('import-history-list').innerHTML='<p class="sheet-copy management-empty">暂无导入记录。确认导入文件后，会在这里显示。</p>';
   $('import-history-more').hidden=!data.hasMore;
   $('import-history-list').querySelectorAll('[data-import-batch]').forEach(button=>button.onclick=()=>detail(button.dataset.importBatch));
  })}
  $('import-history-more').onclick=more;$('import-history-back').onclick=showDataTools;more();
 })}
 function detail(uid){attempt(()=>{
  const data=call('importBatchDetail',uid,0),b=data.batch;
  showSheet('本次导入',`<div class="management-detail"><b class="management-filename">${esc(b.fileName)}</b><p class="sheet-copy">${esc(b.source)} · ${esc(date(b.importedAt))}<br>${esc(date(b.from))}${b.through?' 至 '+esc(date(b.through)):''}</p><dl class="batch-totals"><div><dt>新增账单</dt><dd>${b.created} 笔（导入时待确认 ${b.review} 笔）</dd></div><div><dt>更新已有账单</dt><dd>${b.updated} 笔</dd></div><div><dt>重复跳过 / 无效或不支持</dt><dd>${b.duplicates} / ${b.invalid} 行</dd></div><div><dt>当前可管理</dt><dd>${b.remaining} 笔</dd></div></dl><p class="sheet-copy">下方只列本次新增且尚未删除的账单。更新已有账单的退款等信息不在本次删除范围内；账户和分类会保留。</p><div id="import-batch-rows"></div><button type="button" class="management-more" id="import-batch-more">加载更多账单</button><div class="sheet-actions"><button type="button" id="import-batch-back">返回记录</button><button type="button" class="danger" id="import-batch-delete" ${!b.remaining?'disabled':''}>删除本次新增账单</button></div></div>`);
  let offset=0;
  function append(result){offset+=result.rows.length;$('import-batch-rows').insertAdjacentHTML('beforeend',result.rows.map(r=>`<div class="management-bill"><span><b>${esc(r.merchant||'未填写商户')}</b><small>${esc(date(r.at))}${r.state==='REVIEW'?' · 待确认':''}</small></span><strong>${r.direction==='INCOME'?'+':'−'}¥${money(r.amount)}</strong></div>`).join(''));$('import-batch-more').hidden=!result.hasMore}
  append(data);$('import-batch-more').onclick=()=>attempt(()=>append(call('importBatchDetail',uid,offset)));
  $('import-batch-back').onclick=history;$('import-batch-delete').onclick=()=>confirmDelete(uid);
 })}
 function confirmDelete(uid){attempt(()=>{
  const p=call('previewBatchDelete',JSON.stringify({importBatch:uid,all:true,ids:[],excluded:[]}));
  if(!p.count){notify('本次导入已无可删除账单');detail(uid);return}
  showSheet(`删除本次导入的 ${p.count} 笔账单？`,`<p class="sheet-copy management-filename">${esc(p.batch.fileName)}<br>${esc(p.batch.source)} · ${esc(date(p.batch.importedAt))}<br>原导入范围：${esc(date(p.batch.from))} 至 ${esc(date(p.batch.through))}</p><dl class="batch-totals"><div><dt>当前收入合计</dt><dd>¥${money(p.income)}</dd></div><div><dt>当前支出合计</dt><dd>¥${money(p.expense)}</dd></div></dl><p class="sheet-copy">含 ${p.reviewCount} 笔待确认、${p.editedCount||0} 笔导入后有修改的账单，按当前金额处理。更新过的已有账单不删除。总账和统计同步更新，余额按每笔账单的设置处理，保留账户与分类。10 秒内可整批撤销。</p><div class="sheet-actions"><button type="button" id="import-delete-cancel">返回</button><button type="button" class="danger" id="import-delete-confirm">确认删除</button></div>`);
  $('import-delete-cancel').onclick=()=>detail(uid);
  $('import-delete-confirm').onclick=()=>{const button=$('import-delete-confirm');button.disabled=true;try{
   const result=call('deleteBatch',p.token);closeSheet();viewKey='';loadedScope='';refresh();showUndo(result);
  }catch(error){detail(uid);notify(error.message)}};
 })}
 function storage(){attempt(()=>{
  const s=call('screenshotStorageInfo');screenshotStorage=s;summary();
  showSheet('截图管理',`<div class="storage-overview"><span>当前占用 · ${s.count} 张截图</span><strong>${size(s.bytes)}</strong><small>上限 ${size(s.maxBytes)} · 暂时保护 ${size(s.protectedBytes)}</small></div>${s.bytes>s.maxBytes?'<p class="sheet-copy storage-over-limit">当前超过上限。正在识别、排队和撤销期内的截图受到保护，任务结束后继续清理。</p>':''}<label class="storage-limit-label" for="screenshot-limit">截图容量上限（MiB）</label><input id="screenshot-limit" class="storage-limit-input" type="number" inputmode="numeric" min="50" max="5120" step="1" value="${s.maxBytes/1048576}" aria-describedby="screenshot-limit-help"/><div class="storage-presets">${[100,300,500,1024,2048].map(n=>`<button type="button" data-storage-preset="${n}">${n} MiB</button>`).join('')}</div><p id="screenshot-limit-help" class="sheet-copy">可设置 50～5120 MiB，默认 500 MiB。超限时按旧图优先清理，包括旧账单的截图。仅清理应用内副本，保留账单、识别文字、账户、商户图标和相册原图。已清理的截图无法在应用内恢复。</p><p class="sheet-copy">正在识别、排队和删除撤销期的截图暂不清理，可能短时超限。</p><div class="sheet-actions"><button type="button" id="storage-clean">按当前上限清理</button><button type="button" class="primary" id="storage-save">保存上限</button></div>`);
  document.querySelectorAll('[data-storage-preset]').forEach(button=>button.onclick=()=>{$('screenshot-limit').value=button.dataset.storagePreset});
  $('storage-save').onclick=()=>previewStorage($('screenshot-limit').value);
  $('storage-clean').onclick=()=>previewStorage(String(s.maxBytes/1048576));
 })}
 function previewStorage(value){attempt(()=>{
  if(!/^\d+$/.test(value)||Number(value)<50||Number(value)>5120)throw Error('请输入 50～5120 MiB 的整数');
  const p=call('previewScreenshotLimit',Number(value));
  showSheet('确认截图设置',`<p class="sheet-copy">容量上限：${size(p.proposedBytes)}</p><dl class="batch-totals"><div><dt>预计清理</dt><dd>${p.removeCount} 张 / ${size(p.removeBytes)}</dd></div><div><dt>预计剩余</dt><dd>${size(p.projectedBytes)}</dd></div></dl><p class="sheet-copy">清理包含超过保留期限的临时截图，以及超出容量预算的旧截图。账单数据和相册原图保留。${p.projectedBytes>p.proposedBytes?'正在使用的截图暂时受到保护，稍后继续清理。':''}</p><div class="sheet-actions"><button type="button" id="storage-cancel">返回修改</button><button type="button" class="${p.removeCount?'danger':'primary'}" id="storage-confirm">${p.removeCount?'保存并清理':'确认保存'}</button></div>`);
  $('storage-cancel').onclick=storage;
  $('storage-confirm').onclick=()=>{const button=$('storage-confirm');button.disabled=true;try{const result=call('applyScreenshotLimit',p.token);refresh();storage();notify(`设置已保存，释放 ${size(result.storage.freedBytes)}`)}catch(error){storage();notify(error.message)}};
 })}
 const renderBefore=window.render;window.render=function(){renderBefore();summary()};
 window.DataManagement={history,storage};summary();
})();
