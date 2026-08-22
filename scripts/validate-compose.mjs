import { readFile } from 'node:fs/promises'
import { parse } from 'yaml'

const compose = parse(await readFile('deploy/docker-compose.yml', 'utf8'))
if (compose.name !== 'quotation-prod') throw new Error('Compose project name must be quotation-prod')
const services = Object.entries(compose.services || {})
if (services.length !== 5) throw new Error(`Expected five isolated services, found ${services.length}`)
for (const [name, service] of services) {
  if (!name.startsWith('quotation-')) throw new Error(`Unscoped service name: ${name}`)
  if ('ports' in service) throw new Error(`Published host ports are forbidden: ${name}`)
}
for (const volume of Object.keys(compose.volumes || {})) if (!volume.startsWith('quotation-')) throw new Error(`Unscoped volume: ${volume}`)
if (!compose.networks?.['quotation-internal']?.internal) throw new Error('quotation-internal must be an internal network')
if (!compose.networks?.['ahmln-edge']?.external) throw new Error('ahmln-edge must be external')
process.stdout.write(`Compose validation passed: ${services.length} isolated services, zero published ports\n`)
