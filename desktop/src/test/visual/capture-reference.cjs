const {chromium}=require('playwright');
const fs=require('fs');
const {pathToFileURL}=require('url');
(async()=>{
  const browser=await chromium.launch({channel:'chrome',headless:true});
  const page=await browser.newPage({viewport:{width:1112,height:900},deviceScaleFactor:1});
  await page.goto(pathToFileURL(process.cwd()+'/docs/design/prototype.html').href);
  const scenarios=await page.locator('#scenario option').evaluateAll(options=>options.map(o=>({id:o.value,title:o.textContent})));
  fs.mkdirSync('desktop/target/visual/reference',{recursive:true});
  for(const item of scenarios){
    await page.selectOption('#scenario',item.id);
    await page.evaluate(()=>document.fonts.ready);
    const b=await page.locator('.app').boundingBox();
    await page.screenshot({path:`desktop/target/visual/reference/${item.id}.png`,clip:{x:b.x+1,y:b.y+35,width:1070,height:700}});
  }
  fs.writeFileSync('desktop/target/visual/scenarios.json',JSON.stringify(scenarios,null,2));
  await browser.close();
  process.stdout.write(JSON.stringify({captured:scenarios.length}));
})().catch(e=>{console.error(e);process.exit(1);});
