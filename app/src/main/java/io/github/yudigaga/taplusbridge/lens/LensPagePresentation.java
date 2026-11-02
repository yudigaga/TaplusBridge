package io.github.yudigaga.taplusbridge.lens;

/** Hides Lens controls duplicated by the native result sheet while keeping its real page content. */
final class LensPagePresentation {
    private LensPagePresentation() { }

    static final String HIDE_CONTROLS = """
            (function(){
              const validPage=()=>{
                const url=new URL(location.href);
                return ['google.com','www.google.com','lens.google.com'].includes(url.hostname)
                  &&url.pathname==='/search';
              };
              if(!validPage())return;
              const key='taplusLensHidden', labels=['AI模式','全部','完全匹配','外观匹配'];
              const norm=s=>(s||'').split(String.fromCharCode(8203)).join('').trim();
              const compact=s=>norm(s).split(String.fromCharCode(32)).join('').split(String.fromCharCode(160)).join('')
                .split(String.fromCharCode(9)).join('').split(String.fromCharCode(10)).join('').split(String.fromCharCode(13)).join('');
              const box=e=>{const r=e.getBoundingClientRect();return r.width>innerWidth*.55&&r.height>=24&&r.height<=220?r:null};
              const hide=e=>{if(!e||e.dataset[key]==='1')return;e.dataset[key]='1';
                e.dataset.taplusLensDisplay=e.style.getPropertyValue('display');
                e.dataset.taplusLensPriority=e.style.getPropertyPriority('display');
                e.style.setProperty('display','none','important')};
              const smallest=matches=>{
                let best=null,area=Infinity;
                for(const e of document.querySelectorAll('nav,[role="tablist"],section,div,form')){
                  if(e.dataset[key]==='1')continue;
                  const raw=e.textContent||'';
                  if(!raw||raw.length>300)continue;
                  const text=compact(raw);
                  if(!matches(text))continue;
                  const r=box(e);if(!r)continue;
                  const size=r.width*r.height;if(size<area){best=e;area=size}
                }
                return best;
              };
              const run=()=>{
                if(!validPage())return;
                const tabs=smallest(text=>labels.filter(label=>text.includes(label)).length>=3);
                if(tabs)hide(tabs);
                const phrases=['添加更多搜索条件','Addtosearch','Addmoresearchterms'];
                const search=smallest(text=>phrases.some(phrase=>text.includes(compact(phrase))));
                if(search)hide(search);
              };
              run();
              if(!window.__taplusLensControlsObserver&&document.body){
                let queued=false;
                window.__taplusLensControlsObserver=new MutationObserver(()=>{
                  if(queued)return;queued=true;setTimeout(()=>{queued=false;run()},100)
                });
                window.__taplusLensControlsObserver.observe(document.body,{childList:true,subtree:true,characterData:true});
              }
            })();
            """;

    static final String RESTORE_CONTROLS = """
            (function(){
              const observer=window.__taplusLensControlsObserver;
              if(observer){observer.disconnect();delete window.__taplusLensControlsObserver}
              for(const e of document.querySelectorAll('[data-taplus-lens-hidden="1"]')){
                const value=e.dataset.taplusLensDisplay||'',priority=e.dataset.taplusLensPriority||'';
                if(value)e.style.setProperty('display',value,priority);else e.style.removeProperty('display');
                delete e.dataset.taplusLensHidden;delete e.dataset.taplusLensDisplay;delete e.dataset.taplusLensPriority;
              }
            })();
            """;
}
