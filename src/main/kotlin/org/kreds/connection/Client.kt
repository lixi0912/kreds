package org.kreds.connection

import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kreds.Argument
import org.kreds.commands.*
import org.kreds.protocol.*


object KredsClientGroup{
    private val eventLoopGroup = NioEventLoopGroup()
    fun newClient(endpoint: Endpoint): KredsClient =
        DefaultKredsClient(endpoint, eventLoopGroup)
    fun newSubscriberClient(endpoint: Endpoint, handler: KredsSubscriber): KredsSubscriberClient =
        DefaultKredsSubscriberClient(endpoint, eventLoopGroup, handler)
    suspend fun shutdown(){
        eventLoopGroup.shutdownGracefully().suspendableAwait()
    }
}

interface KredsClient: KeyCommands,StringCommands,ConnectionCommands,PublisherCommands{
    fun pipelined(): Pipeline
    fun multi(): Transaction
}

abstract class AbstractKredsClient(endpoint: Endpoint,eventLoopGroup: EventLoopGroup):DefaultKConnection(endpoint,eventLoopGroup), CommandExecutor{

    protected val mutex = Mutex()

    override suspend fun <T> execute(command: Command, processor: ICommandProcessor, vararg args: Argument): T {
        return mutex.withLock {
            writeAndFlush(processor.encode(command,*args))
            processor.decode(readChannel.receive())
        }
    }

    override suspend fun <T> execute(commandExecution: CommandExecution): T {
        return mutex.withLock {
            with(commandExecution){
                writeAndFlush(processor.encode(command,*args))
                processor.decode(readChannel.receive())
            }
        }
    }
}
class DefaultKredsClient(endpoint: Endpoint,eventLoopGroup: EventLoopGroup):AbstractKredsClient(endpoint, eventLoopGroup),KredsClient,PipelineExecutor, TransactionExecutor, KeyCommandExecutor, StringCommandsExecutor, ConnectionCommandsExecutor, PublishCommandExecutor{

    override fun pipelined(): Pipeline = PipelineImpl(this)
    override fun multi(): Transaction = TransactionImpl(this)

    override suspend fun executePipeline(commands: List<CommandExecution>): List<Any?>  = mutex.withLock {
        val head = commands.dropLast(1)
        head.forEach{
            with(it){
                write(processor.encode(command,*args))
            }
        }
        with(commands.last()){
            writeAndFlush(processor.encode(command,*args))
        }
        // collect the response.
        val responseList = mutableListOf<Any?>()
        repeat(commands.size){
            val cmd = commands[it]
            responseList.add(it,cmd.processor.decode(readChannel.receive()))
        }
        responseList
    }

    override suspend fun executeTransaction(commands: List<CommandExecution>): List<Any?> = mutex.withLock {
        val head = commands.dropLast(1)
        head.forEach{
            with(it){
                write(processor.encode(command,*args))
            }
        }
        with(commands.last()){
            writeAndFlush(processor.encode(command,*args))
        }

        // Drop all the QUEUED messages
        repeat(commands.size - 1){
            readChannel.receive()
        }
        commands.last().processor.decode(readChannel.receive())
    }
}

