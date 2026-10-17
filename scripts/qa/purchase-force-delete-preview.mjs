// Isolated UI fixture: every /api request is handled here; no production upstream.
import { createServer } from 'vite'

const fixture = { sku:'ZS2600456', category:'宠物用品', catalogState:'disabled', quoteReady:false,
  weightG:95, minOrderQty:1, purchasePriceCny:4.28, singleFreightCny:2.5, freight10Cny:5.5,
  freight100Cny:50.5, quotationOwner:'本地功能验收', stockStatus:'有货', dataSource:'standard', _version:2,
  priceTiers:[{minQty:1,maxQty:null,unitPriceCny:4.28}], productImage:'',physicalImage:'' }
const server=await createServer({server:{host:'127.0.0.1',port:5187,strictPort:true},plugins:[{
  name:'isolated-force-delete-preview',configureServer(server){server.middlewares.use((req,res,next)=>{
    const path=new URL(req.url,'http://127.0.0.1').pathname
    if(!path.startsWith('/api/'))return next()
    // Preview intentionally refuses mutations, including the final confirmation button.
    if(req.method!=='GET'){res.writeHead(405,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'PREVIEW_ONLY',message:'本地预览不执行删除',data:null}));return}
    let data={}
    if(path.endsWith('/auth/me'))data={id:'local-admin',account:'LOCALADMIN',name:'本地功能验收',role:'super_admin',permissions:['purchase','quote','myRecords','allRecords','permissions'],mustChangePassword:false}
    else if(path.endsWith('/purchase-catalog'))data={items:[fixture],page:0,size:10,total:1,totalPages:1}
    else if(path.endsWith('/purchase-products/stats'))data={total:1,ready:0,pending:1,generatedSku:0}
    else if(path.endsWith('/deletion-check'))data={canDelete:false,version:2,imageCount:1,quotationRecords:1,drafts:0,templates:0,importBatches:0}
    else if(path.endsWith('/purchase-products/ZS2600456'))data=fixture
    else if(path.includes('notifications'))data={items:[],unread:0,unreadCount:0,total:0}
    else if(path.includes('rankings'))data={items:[],total:0}
    else if(path.includes('sales'))data={source:{period:'6—8月',rows:[],monthlyOnlySkus:[]},products:[],matchedAt:new Date().toISOString()}
    res.writeHead(200,{'Content-Type':'application/json','Cache-Control':'no-store'})
    res.end(JSON.stringify({code:'OK',message:'',data,requestId:'local-preview',fieldErrors:[],timestamp:new Date().toISOString()}))
  })}
}]})
await server.listen()
console.log('Local force-delete preview: http://127.0.0.1:5187/quotation/products')
