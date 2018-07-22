"""Custom topology example

Two directly connected switches plus a host for each switch:

   host --- switch --- switch --- host

Adding the 'topos' dict with a key/value pair to generate our newly defined
topology enables one to pass in '--topo=mytopo' from the command line.
"""

from mininet.topo import Topo

class MyTopo( Topo ):
    "Simple topology example."

    def __init__( self ):
        "Create custom topo."

        # Initialize topology
        Topo.__init__( self )

        # Add hosts and switches
        leftHost = self.addHost( 'h1' )
        middleHost = self.addHost('h2')
        rightHost = self.addHost( 'h3' )
        leftSwitch = self.addSwitch( 's1' )
        middleSwitch = self.addSwitch('s2')
        rightSwitch = self.addSwitch( 's3' )


        # Add links
        self.addLink( leftHost, leftSwitch )
        self.addLink( leftSwitch, middleSwitch)
        self.addLink( middleSwitch, middleHost)
        self.addLink( middleSwitch, rightSwitch)
        self.addLink( leftSwitch, rightSwitch )
        self.addLink( rightSwitch, rightHost )


topos = { 'mytopo': ( lambda: MyTopo() ) }

