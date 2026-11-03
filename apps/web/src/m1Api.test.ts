import {afterEach,expect,it,vi} from 'vitest'
import {campusInstant,m1Get} from './m1Api'
afterEach(()=>vi.unstubAllGlobals())
it('uses the M1 prefix and cookie without write or service credentials',async()=>{
  const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({items:[]})));vi.stubGlobal('fetch',fetch)
  await m1Get('/rooms');expect(fetch).toHaveBeenCalledWith('/api/m1/v1/rooms',{credentials:'same-origin'})
})
it('only a browser 401 expires the session; D unavailability preserves it',async()=>{
  const spy=vi.spyOn(window,'dispatchEvent');const fetch=vi.fn();vi.stubGlobal('fetch',fetch)
  fetch.mockResolvedValueOnce(new Response(JSON.stringify({message:'暂不可用'}),{status:503}))
  await expect(m1Get('/rooms')).rejects.toMatchObject({status:503});expect(spy).not.toHaveBeenCalled()
  fetch.mockResolvedValueOnce(new Response('{}',{status:401}));await expect(m1Get('/rooms')).rejects.toMatchObject({status:401});expect(spy).toHaveBeenCalledWith(expect.objectContaining({type:'session-expired'}));spy.mockRestore()
})
it('converts Shanghai wall time and rejects non whole hours',()=>{expect(campusInstant('2026-10-29T09:00')).toBe('2026-10-29T01:00:00Z');expect(()=>campusInstant('2026-10-29T09:15')).toThrow()})
