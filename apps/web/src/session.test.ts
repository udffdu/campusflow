import {beforeEach,expect,it,vi} from 'vitest'
import {can,inScope,landingPath,session} from './session'
beforeEach(()=>{session.user={id:'user-1',username:'member',displayName:'成员',departmentId:'EIE',active:true,roles:['SPACE_MANAGER'],permissions:['spaces.read','spaces.write']}})
it('only exposes granted permissions and the assigned department',()=>{expect(can('spaces.write')).toBe(true);expect(can('users.manage')).toBe(false);expect(inScope('EIE')).toBe(true);expect(inScope('SCI')).toBe(false);expect(inScope(null)).toBe(false)})
it('allows administrators global scope and removes rights when logged out',()=>{session.user!.roles=['ADMIN'];expect(inScope(null)).toBe(true);session.user=null;expect(can('spaces.read')).toBe(false);expect(inScope('EIE')).toBe(false)})

it('lands a seat-only user on query without requiring spaces.read',()=>{session.user!.permissions=['seats.availability.read'];expect(landingPath()).toBe('/seat-query');session.user!.permissions.push('spaces.read');expect(landingPath()).toBe('/')})
it('lands a read-own-only user on their history',()=>{session.user!.permissions=['seats.reservations.read.own'];expect(landingPath()).toBe('/my-reservations')})
