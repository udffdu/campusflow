import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import ResourcesView from '../views/ResourcesView.vue'
import {session} from '../session'
import type {Space} from '../types'

const {apiMock}=vi.hoisted(()=>({apiMock:vi.fn()}))
vi.mock('../api',()=>({api:apiMock,allPages:async()=>[],message:(e:Error)=>e.message}))
let wrapper:VueWrapper|undefined
let resource:Space
let writes:Record<string,unknown>[]
beforeEach(()=>{
  writes=[]
  resource={id:'room',type:'ROOM',parentId:'building',code:'A101',name:'A101教室',departmentId:'EIE',capacity:40,
    features:['投影仪'],openingHours:[],active:true,effectiveActive:true,version:7,updatedAt:'2026-10-28T00:00:00Z',bookingScope:'CAMPUS'}
  session.user={id:'manager',username:'manager',displayName:'管理员',departmentId:'EIE',active:true,
    roles:['SPACE_MANAGER'],permissions:['spaces.read','spaces.write']}
  apiMock.mockImplementation(async(path:string,method?:string,body?:Record<string,unknown>)=>{
    if(path==='/departments')return[{id:'EIE',code:'EIE',name:'信息工程学院'}]
    if(method==='PUT'){writes.push(body!);resource={...resource,...body} as Space;return resource}
    return{items:[resource],page:0,size:20,total:1}
  })
})
afterEach(()=>{wrapper?.unmount();wrapper=undefined})
async function edit(){
  wrapper=mount(ResourcesView,{global:{stubs:{RouterLink:true}}});await flushPromises()
  await wrapper.findAll('button').find(b=>b.text()==='编辑')!.trigger('click');await flushPromises()
}

it('loads and saves an explicit room booking scope with version protection',async()=>{
  await edit()
  const select=wrapper!.findAll('label').find(label=>label.text().startsWith('预约开放范围'))!.get('select')
  expect((select.element as HTMLSelectElement).value).toBe('CAMPUS')
  await select.setValue('DEPARTMENT');await wrapper!.get('form').trigger('submit');await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({bookingScope:'DEPARTMENT',expectedVersion:7,features:['投影仪']})
})

it('treats a legacy room without the new field as department-only when edited',async()=>{
  delete resource.bookingScope
  await edit();await wrapper!.get('form').trigger('submit');await flushPromises()
  expect(writes[0]).toMatchObject({bookingScope:'DEPARTMENT'})
})

it('adds and removes the seat outlet marker without changing unrelated free-text features',async()=>{
  resource={...resource,id:'seat',type:'SEAT',parentId:'room',capacity:1,bookingScope:null,features:['靠窗','电源']}
  await edit()
  const checkbox=wrapper!.findAll('label').find(label=>label.text()==='有插座')!.get('input')
  expect((checkbox.element as HTMLInputElement).checked).toBe(false)
  await checkbox.setValue(true);await wrapper!.get('form').trigger('submit');await flushPromises()
  expect(writes[0]).toMatchObject({features:['靠窗','电源','POWER_OUTLET'],expectedVersion:7})
  expect(writes[0]).not.toHaveProperty('bookingScope')
  await wrapper!.findAll('button').find(b=>b.text()==='编辑')!.trigger('click');await flushPromises()
  const checked=wrapper!.findAll('label').find(label=>label.text()==='有插座')!.get('input')
  expect((checked.element as HTMLInputElement).checked).toBe(true)
  await checked.setValue(false);await wrapper!.get('form').trigger('submit');await flushPromises()
  expect(writes[1]).toMatchObject({features:['靠窗','电源']})
})

it('shows a readable outlet label to members and keeps editing controls restricted',async()=>{
  resource={...resource,type:'SEAT',bookingScope:null,features:['POWER_OUTLET']}
  session.user!.roles=['MEMBER'];session.user!.permissions=['spaces.read']
  wrapper=mount(ResourcesView,{global:{stubs:{RouterLink:true}}});await flushPromises()
  expect(wrapper.findAll('button').some(b=>b.text()==='编辑'||b.text().includes('新增空间'))).toBe(false)
  await wrapper.findAll('button').find(b=>b.text()==='详情')!.trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('有插座');expect(wrapper.text()).not.toContain('POWER_OUTLET')
})
