import {afterEach,expect,it,vi} from 'vitest'

afterEach(()=>{vi.unstubAllGlobals();vi.resetModules()})

it('broadcasts only an opaque session-change event and notifies local subscribers',async()=>{
  const posted:unknown[]=[]
  let receiver:((event:{data:unknown})=>void)|undefined
  class FakeBroadcastChannel {
    constructor(readonly name:string){}
    addEventListener(_type:string,listener:(event:{data:unknown})=>void){receiver=listener}
    postMessage(message:unknown){posted.push(message)}
    close(){}
  }
  vi.stubGlobal('BroadcastChannel',FakeBroadcastChannel)
  Object.defineProperty(window,'BroadcastChannel',{configurable:true,value:FakeBroadcastChannel})
  const sync=await import('./session')
  const listener=vi.fn();const stop=sync.onSessionChange(listener)
  sync.announceSessionChange()
  expect(posted).toEqual([{type:'session-changed'}])
  expect(JSON.stringify(posted)).not.toContain('user')
  receiver?.({data:{type:'session-changed'}});expect(listener).toHaveBeenCalledTimes(1)
  stop();receiver?.({data:{type:'session-changed'}});expect(listener).toHaveBeenCalledTimes(1)
})
