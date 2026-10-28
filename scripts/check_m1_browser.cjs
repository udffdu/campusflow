// Optional real browser verification over the Vite same-origin proxy. No credentials or headers are logged.
const {chromium}=require('playwright')
// Use polling assertions without adding a test runner dependency to the web application.
function expect(target){return{
  async toHaveURL(pattern){await target.waitForURL(pattern)},
  async toBeVisible(){await target.waitFor({state:'visible'})},
  async toHaveCount(n){await poll(async()=>await target.count()===n)},
  async toContainText(text){await poll(async()=>(await target.textContent()||'').includes(text))}
}}
async function poll(check){for(let i=0;i<150;i++){if(await check())return;await new Promise(r=>setTimeout(r,100))}throw new Error('browser assertion timed out')}
const fs=require('node:fs')
const path=require('node:path')
;(async()=>{
  const browser=await chromium.launch({headless:true,channel:process.env.HTTP_BROWSER_CHANNEL||'chrome'})
  try {
    const context=await browser.newContext({viewport:{width:1440,height:1000}})
    const page=await context.newPage()
    const requests=[];const errors=[]
    page.on('pageerror',e=>errors.push(e.name))
    page.on('response',response=>{const url=new URL(response.url());if(url.pathname.startsWith('/api/m1/'))requests.push({path:url.pathname,status:response.status(),origin:url.origin})})
    await page.goto(process.env.HTTP_BROWSER_URL+'/login')
    await page.getByLabel('账号',{exact:true}).fill('student')
    await page.getByLabel('密码',{exact:true}).fill(process.env.HTTP_BROWSER_PASSWORD)
    await page.getByRole('button',{name:'登录工作空间'}).click()
    await expect(page).toHaveURL(/\/seat-query$/)
    await expect(page.getByRole('heading',{name:'座位查询',exact:true})).toBeVisible()
    await expect(page.getByRole('link',{name:'空间资源',exact:true})).toHaveCount(0)
    const campusTime=value=>new Date(new Date(value).getTime()+8*3600000).toISOString().slice(0,16)
    await page.getByLabel('开始时间（上海）').fill(campusTime(process.env.HTTP_BROWSER_START))
    await page.getByLabel('结束时间（上海）').fill(campusTime(process.env.HTTP_BROWSER_END))
    await page.getByRole('button',{name:'查询候选座位'}).click()
    await expect(page.locator('.candidate-card')).toHaveCount(4)
    await expect(page.getByText('当前仅支持查询，预约暂未开放')).toBeVisible()
    await page.getByLabel('只看有插座的座位').check()
    await expect(page.locator('.candidate-card')).toHaveCount(0)
    await page.getByRole('button',{name:'查询候选座位'}).click()
    await expect(page.locator('.candidate-card')).toHaveCount(1)
    await expect(page.locator('.candidate-card')).toContainText('A101-S003')
    const out=path.resolve(__dirname,'../tmp/m1-http-browser');fs.mkdirSync(out,{recursive:true})
    await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true})
    const mobile=await page.context().newPage()
    await mobile.setViewportSize({width:390,height:844})
    await mobile.goto(process.env.HTTP_BROWSER_URL+'/seat-query')
    await mobile.getByLabel('开始时间（上海）').fill(campusTime(process.env.HTTP_BROWSER_START))
    await mobile.getByLabel('结束时间（上海）').fill(campusTime(process.env.HTTP_BROWSER_END))
    await mobile.getByLabel('只看有插座的座位').check()
    await mobile.getByRole('button',{name:'查询候选座位'}).click()
    await expect(mobile.locator('.candidate-card')).toHaveCount(1)
    const overflow=await mobile.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth)
    if(overflow)throw new Error('mobile horizontal overflow')
    await mobile.screenshot({path:path.join(out,'mobile.png'),fullPage:true,animations:'disabled'})
    await mobile.close()
    // Frontend fault injection is separate from the actual D outage test in check_m1_http.py.
    await page.route('**/api/m1/v1/seat-candidates?**',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'D_UNAVAILABLE',message:'身份或资源服务暂不可用，请稍后重试'})}))
    await page.getByRole('button',{name:'查询候选座位'}).click()
    await expect(page.getByRole('alert')).toContainText('暂不可用')
    await expect(page).toHaveURL(/\/seat-query$/)
    await expect(page.locator('.candidate-card')).toHaveCount(0)
    await page.unroute('**/api/m1/v1/seat-candidates?**')
    await page.reload()
    await expect(page.getByRole('heading',{name:'座位查询',exact:true})).toBeVisible()
    await page.getByRole('button',{name:'退出登录',exact:true}).click()
    await expect(page).toHaveURL(/\/login$/)
    await page.getByLabel('账号',{exact:true}).fill('manager')
    await page.getByLabel('密码',{exact:true}).fill(process.env.HTTP_BROWSER_PASSWORD)
    await page.getByRole('button',{name:'登录工作空间'}).click()
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByRole('link',{name:'座位查询',exact:true})).toHaveCount(0)
    await page.goto(process.env.HTTP_BROWSER_URL+'/seat-query')
    await expect(page).toHaveURL(/\/forbidden$/)
    if(errors.length)throw new Error('browser runtime errors: '+errors.join(','))
    if(!requests.some(r=>r.path.endsWith('/rooms')&&r.status===200)||!requests.some(r=>r.path.endsWith('/seat-candidates')&&r.status===200))throw new Error('missing successful proxy requests')
    if(requests.some(r=>r.origin!==process.env.HTTP_BROWSER_URL))throw new Error('browser query bypassed proxy')
    const report={result:'passed',normal_chain:'browser -> Vite proxy -> real M1 -> real D',requests,desktop:'desktop.png',mobile:'mobile.png',horizontalOverflow:overflow,consoleErrors:errors.length,credentialsLogged:false,fault503:'frontend response injection; real D outage separately checked by Python'}
    fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n')
    console.log('PASS real browser login, M1-only landing, query, power, proxy, refresh, read-only controls, 503 stays logged in, permissions, desktop/mobile')
  } finally {await browser.close()}
})().catch(error=>{console.error(error.message);process.exitCode=1})
